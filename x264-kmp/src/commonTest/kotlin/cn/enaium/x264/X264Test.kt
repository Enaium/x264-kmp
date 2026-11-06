/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.x264

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class X264Test {
    private val width = 320
    private val height = 240

    @Test
    fun versionMatchesThePinnedRelease() {
        assertEquals("1.0.0", X264.VERSION)
        assertEquals("0.165.x", X264.libraryVersion)
        assertEquals(165, X264.libraryVersionPacked)
        assertEquals(165, X264.apiBuild)
        assertEquals(8, X264.BIT_DEPTH)
    }

    @Test
    fun encoderEmitsParameterSetsAndAnnexBStream() {
        X264Param.create(width, height, X264ChromaFormat.I420, X264Preset.ULTRAFAST).use { param ->
            param.set("crf", 30)
            param.set("bframes", 0)
            param.set("keyint", 10)
            param.logLevel = X264LogLevel.NONE
            assertEquals(320, param.width)
            assertEquals(240, param.height)
            assertEquals(X264ChromaFormat.I420, param.chromaFormat)
            assertTrue(param.validate())

            X264Encoder.open(param).use { encoder ->
                assertEquals(8, encoder.parameters.bitDepth)

                val headers = encoder.headers()
                assertTrue(headers.nals.any { it.nalType == X264NalType.SPS }, "SPS missing")
                assertTrue(headers.nals.any { it.nalType == X264NalType.PPS }, "PPS missing")
                assertTrue(headers.nals.any { it.nalType == X264NalType.SEI }, "SEI missing")

                val csd = headers.parameterSets()
                val parameterSetNals = headers.nals.filter { it.isParameterSet }
                assertEquals(2, parameterSetNals.size, "SPS/PPS expected")
                assertEquals(parameterSetNals.sumOf { it.data.size } + 8, csd.size)
                assertContentEquals(byteArrayOf(0, 0, 0, 1), csd.copyOfRange(0, 4))
                assertFalse(
                    csd.containsSequence(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1)),
                    "csd-0 contains a doubled start code",
                )

                val frames = ArrayList<X264Frame>()
                repeat(6) { index ->
                    encoder.encode(gradient(index))?.let(frames::add)
                }
                frames.addAll(encoder.flush())

                assertTrue(frames.isNotEmpty(), "encoder produced no pictures")
                assertTrue(frames.first().isKeyFrame, "first picture must be a keyframe")
                assertTrue(frames.all { it.byteSize > 0 }, "empty picture")
                assertTrue(frames.any { frame -> frame.nals.any { it.nalType?.isSlice == true } })

                // x264 frames NAL units internally (start code or length
                // prefix); the binding strips that so each payload starts with
                // the one-byte NAL header.
                (headers.nals + frames.flatMap { it.nals }).forEach { nal ->
                    val headerType = nal.data[0].toInt() and 0x1F
                    assertEquals(nal.type, headerType, "NAL ${nal.type} still carries x264 framing")
                }

                val annexB = frames.first().toAnnexB()
                assertContentEquals(byteArrayOf(0, 0, 0, 1), annexB.copyOfRange(0, 4))
                assertEquals(frames.first().nals.sumOf { it.data.size } + 4 * frames.first().nals.size, annexB.size)
                assertFalse(
                    annexB.containsSequence(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1)),
                    "Annex-B output contains a doubled start code",
                )
            }
        }
    }

    @Test
    fun acceptsPaddedStrides() {
        val strideY = width + 64
        val strideU = width / 2 + 32
        val y = ByteArray(strideY * height)
        val u = ByteArray(strideU * (height / 2))
        val v = ByteArray(strideU * (height / 2))
        for (row in 0 until height) {
            for (column in 0 until width) {
                y[row * strideY + column] = ((row + column) and 0xFF).toByte()
            }
        }
        u.fill(128.toByte())
        v.fill(128.toByte())

        X264Param.create(width, height, X264ChromaFormat.I420, X264Preset.ULTRAFAST).use { param ->
            param.set("bframes", 0)
            param.logLevel = X264LogLevel.NONE
            X264Encoder.open(param).use { encoder ->
                encoder.headers()
                val picture = X264Picture(
                    y = y,
                    u = u,
                    v = v,
                    width = width,
                    height = height,
                    chromaFormat = X264ChromaFormat.I420,
                    strideY = strideY,
                    strideU = strideU,
                    strideV = strideU,
                )
                val frame = encoder.encode(picture) ?: encoder.flush().firstOrNull()
                assertTrue(frame != null && frame.byteSize > 0)
            }
        }
    }

    @Test
    fun rejectsUnknownOptions() {
        X264Param.create(width, height).use { param ->
            val error = assertFailsWith<X264Exception> {
                param.set("definitely-not-an-x264-option", 1)
            }
            assertTrue(error.message.orEmpty().contains("definitely-not-an-x264-option"), error.message)
        }
    }

    @Test
    fun rejectsMalformedPictures() {
        assertFailsWith<IllegalArgumentException> {
            X264Picture(ByteArray(4), ByteArray(1), ByteArray(1), 64, 64, X264ChromaFormat.I420)
        }
        assertFailsWith<IllegalArgumentException> {
            X264Picture(ByteArray(64 * 64), null, null, 64, 64, X264ChromaFormat.I420)
        }
    }

    @Test
    fun splitsNv12IntoI420() {
        val chromaSize = (width / 2) * (height / 2)
        val nv12 = ByteArray(width * height + chromaSize * 2)
        nv12[width * height] = 7
        nv12[width * height + 1] = 9
        val picture = X264Picture.ofNv12(nv12, width, height, pts = 42)
        assertEquals(42, picture.pts)
        assertEquals(7, picture.u!![0].toInt())
        assertEquals(9, picture.v!![0].toInt())
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        for (start in 0..(size - sequence.size)) {
            var matches = true
            for (offset in sequence.indices) {
                if (this[start + offset] != sequence[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun gradient(index: Int): X264Picture {
        val y = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                y[row * width + column] = ((column + row + index * 7) and 0xFF).toByte()
            }
        }
        val chromaSize = (width / 2) * (height / 2)
        return X264Picture.i420(
            y = y,
            u = ByteArray(chromaSize) { 128.toByte() },
            v = ByteArray(chromaSize) { 128.toByte() },
            width = width,
            height = height,
            pts = index * 33_333L,
        )
    }
}
