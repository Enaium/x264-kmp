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

/**
 * A configured H.264 encoder.
 *
 * The encoder is stateful and not thread-safe: call [encode] from one thread at
 * a time. On the JVM and Kotlin/Native x264 runs its own worker threads, so the
 * call itself returns as soon as the frame is accepted or a picture falls out
 * of the lookahead/B-frame reorder buffer.
 *
 * ```kotlin
 * val param = X264Param.create(1280, 720).preset(X264Preset.ULTRAFAST).crf(28.0)
 * X264Encoder.open(param).use { encoder ->
 *     encoder.headers()               // SPS/PPS for MediaCodec's csd-0
 *     while (frames.hasNext()) {
 *         encoder.encode(frame)?.let { send(it.toAnnexB()) }
 *     }
 *     encoder.flush().forEach { send(it.toAnnexB()) }
 * }
 * ```
 */
class X264Encoder private constructor(
    private val handle: X264EncoderHandle,
    /** The parameters the encoder actually runs with (after preset and open-time adjustments). */
    val parameters: X264Param,
) : AutoCloseable {
    private var closed = false

    /** True for builds that run x264 without a worker pool (the web targets). */
    val isSingleThreaded: Boolean get() = parameters.frameThreads == 1

    /**
     * Stream headers (SPS, PPS), required as `csd-0` by MediaCodec and by
     * any muxer that does not guess them from the first keyframe.
     *
     * x264 serialises the parameter sets on every call, so calling this again
     * simply returns the same SPS/PPS.
     */
    fun headers(): X264Frame {
        checkOpen()
        val blob = X264Native.encoderHeaders(handle)
            ?: return X264Frame(emptyList(), 0, 0, X264SliceType.AUTO)
        return X264Output.parse(blob)
    }

    /**
     * Encodes one picture.
     *
     * Returns the encoded picture, or `null` when x264 buffered the input for
     * frame reordering. With B-frames enabled several calls may return `null`
     * before a picture comes out; [flush] drains the remainder.
     */
    fun encode(picture: X264Picture): X264Frame? {
        checkOpen()
        val blob = X264Native.encode(handle, picture) ?: return null
        return X264Output.parse(blob)
    }

    /** Encodes one tightly packed picture. */
    fun encode(
        y: ByteArray,
        u: ByteArray?,
        v: ByteArray?,
        width: Int,
        height: Int,
        chromaFormat: X264ChromaFormat = X264ChromaFormat.I420,
        pts: Long = 0L,
    ): X264Frame? = encode(
        X264Picture(
            y = y,
            u = u,
            v = v,
            width = width,
            height = height,
            chromaFormat = chromaFormat,
            pts = pts,
        ),
    )

    /**
     * Drains every delayed picture. Call once after the last input frame;
     * afterwards the encoder must not be used for new input.
     */
    fun flush(): List<X264Frame> {
        checkOpen()
        val frames = ArrayList<X264Frame>()
        while (true) {
            val blob = X264Native.flush(handle) ?: break
            frames.add(X264Output.parse(blob))
        }
        return frames
    }

    override fun close() {
        if (closed) return
        closed = true
        X264Native.encoderClose(handle)
        parameters.close()
    }

    override fun toString(): String = "X264Encoder($parameters)"

    private fun checkOpen() {
        check(!closed) { "X264Encoder is closed" }
    }

    companion object {
        /**
         * Opens an encoder for [parameters].
         *
         * The parameter block is copied; it can be closed or reused immediately
         * after this call. The effective values are available from [parameters]
         * on the returned encoder.
         */
        fun open(parameters: X264Param): X264Encoder {
            if (!parameters.validate()) {
                throw X264Exception(
                    "x264 rejected ${parameters.width}x${parameters.height} " +
                        "${parameters.chromaFormat}: check the size, frame rate and chroma format",
                )
            }
            val handle = X264Native.encoderOpen(parameters.handle)
                ?: throw X264Exception(
                    "x264 failed to open an encoder for ${parameters.width}x${parameters.height} " +
                        "${parameters.chromaFormat}; enable logging with X264LogLevel.INFO for details",
                )
            val effective = try {
                X264Param.create(
                    parameters.width,
                    parameters.height,
                    parameters.chromaFormat,
                )
            } catch (e: Throwable) {
                X264Native.encoderClose(handle)
                throw e
            }
            if (X264Native.encoderParameters(handle, effective.handle) < 0) {
                effective.close()
                X264Native.encoderClose(handle)
                throw X264Exception("x264 did not expose the encoder parameters")
            }
            return X264Encoder(handle, effective)
        }
    }
}
