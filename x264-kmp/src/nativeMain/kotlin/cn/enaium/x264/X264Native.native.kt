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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.x264

import cnames.structs.xk_encoder
import cnames.structs.xk_params
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import x264_kmp.xk_encoder_close
import x264_kmp.xk_encoder_headers
import x264_kmp.xk_encoder_open
import x264_kmp.xk_encoder_parameters
import x264_kmp.xk_encode_planes
import x264_kmp.xk_flush
import x264_kmp.xk_output_blob
import x264_kmp.xk_params_apply_preset
import x264_kmp.xk_params_apply_profile
import x264_kmp.xk_params_apply_tune
import x264_kmp.xk_params_create
import x264_kmp.xk_params_free
import x264_kmp.xk_params_get_int
import x264_kmp.xk_params_set_log_level
import x264_kmp.xk_params_parse
import x264_kmp.xk_params_validate
import x264_kmp.xk_version_packed
import x264_kmp.xk_version_string
import x264_kmp.xk_api_build

internal actual class X264ParamsHandle actual constructor() {
    internal var pointer: CPointer<xk_params>? = null
}

internal actual class X264EncoderHandle actual constructor() {
    internal var pointer: CPointer<xk_encoder>? = null
}

internal actual object X264Native {
    actual fun versionPacked(): Int = xk_version_packed()

    actual fun versionString(): String = xk_version_string()?.toKString() ?: ""

    actual fun apiBuild(): Int = xk_api_build()

    actual fun paramsCreate(width: Int, height: Int, csp: Int): X264ParamsHandle? {
        val pointer = xk_params_create(width, height, csp) ?: return null
        return X264ParamsHandle().also { it.pointer = pointer }
    }

    actual fun paramsFree(handle: X264ParamsHandle) {
        xk_params_free(handle.pointer)
        handle.pointer = null
    }

    actual fun paramsParse(handle: X264ParamsHandle, name: String, value: String?): Int = memScoped {
        xk_params_parse(handle.pointer, name.cstr.ptr, value?.cstr?.ptr)
    }

    actual fun paramsPreset(handle: X264ParamsHandle, preset: String): Int = memScoped {
        xk_params_apply_preset(handle.pointer, preset.cstr.ptr)
    }

    actual fun paramsTune(handle: X264ParamsHandle, tune: String): Int = memScoped {
        xk_params_apply_tune(handle.pointer, tune.cstr.ptr)
    }

    actual fun paramsProfile(handle: X264ParamsHandle, profile: String): Int = memScoped {
        xk_params_apply_profile(handle.pointer, profile.cstr.ptr)
    }

    actual fun paramsValidate(handle: X264ParamsHandle): Int = xk_params_validate(handle.pointer)

    actual fun paramsGetInt(handle: X264ParamsHandle, field: Int): Int =
        xk_params_get_int(handle.pointer, field)

    actual fun paramsSetLogLevel(handle: X264ParamsHandle, level: Int): Int =
        xk_params_set_log_level(handle.pointer, level)

    actual fun encoderOpen(params: X264ParamsHandle): X264EncoderHandle? {
        val pointer = xk_encoder_open(params.pointer) ?: return null
        return X264EncoderHandle().also { it.pointer = pointer }
    }

    actual fun encoderClose(encoder: X264EncoderHandle) {
        xk_encoder_close(encoder.pointer)
        encoder.pointer = null
    }

    actual fun encoderParameters(encoder: X264EncoderHandle, params: X264ParamsHandle): Int =
        xk_encoder_parameters(encoder.pointer, params.pointer)

    actual fun encoderHeaders(encoder: X264EncoderHandle): ByteArray? =
        result(encoder.pointer, xk_encoder_headers(encoder.pointer), "header request")

    actual fun encode(encoder: X264EncoderHandle, picture: X264Picture): ByteArray? {
        val pointer = encoder.pointer ?: throw X264Exception("encoder is closed")
        val u = picture.u
        val v = picture.v
        val result = picture.y.usePinned { y ->
            val yPointer = y.addressOf(0).reinterpret<kotlinx.cinterop.UByteVar>()
            if (u != null && v != null) {
                u.usePinned { uPinned ->
                    v.usePinned { vPinned ->
                        xk_encode_planes(
                            pointer,
                            yPointer,
                            uPinned.addressOf(0).reinterpret(),
                            vPinned.addressOf(0).reinterpret(),
                            picture.strideY,
                            picture.strideU,
                            picture.strideV,
                            picture.width,
                            picture.height,
                            picture.chromaFormat.value,
                            picture.pts,
                        )
                    }
                }
            } else {
                xk_encode_planes(
                    pointer,
                    yPointer,
                    null,
                    null,
                    picture.strideY,
                    picture.strideU,
                    picture.strideV,
                    picture.width,
                    picture.height,
                    picture.chromaFormat.value,
                    picture.pts,
                )
            }
        }
        return result(pointer, result, "encode")
    }

    actual fun flush(encoder: X264EncoderHandle): ByteArray? =
        result(encoder.pointer, xk_flush(encoder.pointer), "flush")

    private fun result(encoder: CPointer<xk_encoder>?, code: Int, what: String): ByteArray? = when {
        encoder == null -> throw X264Exception("encoder is closed")
        code == X264_OK -> memScoped {
            val size = alloc<IntVar>()
            val blob = xk_output_blob(encoder, size.ptr)
                ?: throw X264Exception("x264 produced output for $what but no buffer")
            if (size.value <= 0) {
                throw X264Exception("x264 produced an empty output buffer for $what")
            }
            blob.readBytes(size.value)
        }
        code == X264_BUFFERED -> null
        else -> throw X264Exception("x264 failed during $what (code $code)")
    }
}
