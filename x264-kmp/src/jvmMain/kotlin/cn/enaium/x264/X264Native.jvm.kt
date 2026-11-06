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

// =========================================================================
// JNI bridge: declarations are shared by the JVM and Android targets.
// =========================================================================

internal object JniX264 {
    init {
        NativeLoader.load()
    }

    external fun nativeVersionPacked(): Int

    external fun nativeVersionString(): String

    external fun nativeApiBuild(): Int

    external fun nativeParamsCreate(width: Int, height: Int, csp: Int): Long

    external fun nativeParamsFree(handle: Long)

    external fun nativeParamsParse(handle: Long, name: String, value: String?): Int

    external fun nativeParamsPreset(handle: Long, preset: String): Int

    external fun nativeParamsTune(handle: Long, tune: String): Int

    external fun nativeParamsProfile(handle: Long, profile: String?): Int

    external fun nativeParamsValidate(handle: Long): Int

    external fun nativeParamsGetInt(handle: Long, field: Int): Int

    external fun nativeParamsSetLogLevel(handle: Long, level: Int): Int

    external fun nativeEncoderOpen(handle: Long): Long

    external fun nativeEncoderClose(handle: Long)

    external fun nativeEncoderParameters(encoder: Long, params: Long): Int

    external fun nativeEncoderHeaders(encoder: Long): Int

    external fun nativeEncode(
        encoder: Long,
        y: ByteArray,
        u: ByteArray?,
        v: ByteArray?,
        width: Int,
        height: Int,
        csp: Int,
        strideY: Int,
        strideU: Int,
        strideV: Int,
        pts: Long,
    ): Int

    external fun nativeFlush(encoder: Long): Int

    external fun nativeOutputBlob(encoder: Long): ByteArray?
}

internal actual class X264ParamsHandle actual constructor() {
    internal var pointer: Long = 0
}

internal actual class X264EncoderHandle actual constructor() {
    internal var pointer: Long = 0
}

internal actual object X264Native {
    actual fun versionPacked(): Int = JniX264.nativeVersionPacked()

    actual fun versionString(): String = JniX264.nativeVersionString()

    actual fun apiBuild(): Int = JniX264.nativeApiBuild()

    actual fun paramsCreate(width: Int, height: Int, csp: Int): X264ParamsHandle? {
        val pointer = JniX264.nativeParamsCreate(width, height, csp)
        if (pointer == 0L) return null
        return X264ParamsHandle().also { it.pointer = pointer }
    }

    actual fun paramsFree(handle: X264ParamsHandle) {
        JniX264.nativeParamsFree(handle.pointer)
    }

    actual fun paramsParse(handle: X264ParamsHandle, name: String, value: String?): Int =
        JniX264.nativeParamsParse(handle.pointer, name, value)

    actual fun paramsPreset(handle: X264ParamsHandle, preset: String): Int =
        JniX264.nativeParamsPreset(handle.pointer, preset)

    actual fun paramsTune(handle: X264ParamsHandle, tune: String): Int =
        JniX264.nativeParamsTune(handle.pointer, tune)

    actual fun paramsProfile(handle: X264ParamsHandle, profile: String): Int =
        JniX264.nativeParamsProfile(handle.pointer, profile)

    actual fun paramsValidate(handle: X264ParamsHandle): Int =
        JniX264.nativeParamsValidate(handle.pointer)

    actual fun paramsGetInt(handle: X264ParamsHandle, field: Int): Int =
        JniX264.nativeParamsGetInt(handle.pointer, field)

    actual fun paramsSetLogLevel(handle: X264ParamsHandle, level: Int): Int =
        JniX264.nativeParamsSetLogLevel(handle.pointer, level)

    actual fun encoderOpen(params: X264ParamsHandle): X264EncoderHandle? {
        val pointer = JniX264.nativeEncoderOpen(params.pointer)
        if (pointer == 0L) return null
        return X264EncoderHandle().also { it.pointer = pointer }
    }

    actual fun encoderClose(encoder: X264EncoderHandle) {
        JniX264.nativeEncoderClose(encoder.pointer)
    }

    actual fun encoderParameters(encoder: X264EncoderHandle, params: X264ParamsHandle): Int =
        JniX264.nativeEncoderParameters(encoder.pointer, params.pointer)

    actual fun encoderHeaders(encoder: X264EncoderHandle): ByteArray? {
        val result = JniX264.nativeEncoderHeaders(encoder.pointer)
        if (result < 0) throw X264Exception("x264 rejected the header request (code $result)")
        if (result == X264_BUFFERED) return null
        return JniX264.nativeOutputBlob(encoder.pointer)
            ?: throw X264Exception("x264 produced headers but no output buffer")
    }

    actual fun encode(encoder: X264EncoderHandle, picture: X264Picture): ByteArray? {
        val result = JniX264.nativeEncode(
            encoder.pointer,
            picture.y,
            picture.u,
            picture.v,
            picture.width,
            picture.height,
            picture.chromaFormat.value,
            picture.strideY,
            picture.strideU,
            picture.strideV,
            picture.pts,
        )
        return when (result) {
            X264_OK -> JniX264.nativeOutputBlob(encoder.pointer)
                ?: throw X264Exception("x264 produced a picture but no output buffer")
            X264_BUFFERED -> null
            else -> throw X264Exception("x264 failed to encode a picture (code $result)")
        }
    }

    actual fun flush(encoder: X264EncoderHandle): ByteArray? {
        val result = JniX264.nativeFlush(encoder.pointer)
        return when (result) {
            X264_OK -> JniX264.nativeOutputBlob(encoder.pointer)
                ?: throw X264Exception("x264 produced a picture but no output buffer")
            X264_BUFFERED -> null
            else -> throw X264Exception("x264 failed to flush (code $result)")
        }
    }
}
