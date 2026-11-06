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

internal actual class X264ParamsHandle actual constructor() {
    /** WebAssembly pointer, always 32-bit. */
    internal var pointer: Int = 0
}

internal actual class X264EncoderHandle actual constructor() {
    /** WebAssembly pointer, always 32-bit. */
    internal var pointer: Int = 0
}

internal actual object X264Native {
    private var initialised = false

    private fun ensureInitialised() {
        if (!initialised) {
            webInit()
            initialised = true
        }
    }

    actual fun versionPacked(): Int {
        ensureInitialised()
        return webVersionPacked()
    }

    actual fun versionString(): String {
        ensureInitialised()
        return webVersionString()
    }

    actual fun apiBuild(): Int {
        ensureInitialised()
        return webApiBuild()
    }

    actual fun paramsCreate(width: Int, height: Int, csp: Int): X264ParamsHandle? {
        ensureInitialised()
        val pointer = webParamsCreate(width, height, csp)
        if (pointer == 0) return null
        return X264ParamsHandle().also { it.pointer = pointer }
    }

    actual fun paramsFree(handle: X264ParamsHandle) {
        ensureInitialised()
        webParamsFree(handle.pointer)
        handle.pointer = 0
    }

    actual fun paramsParse(handle: X264ParamsHandle, name: String, value: String?): Int {
        ensureInitialised()
        return webParamsConfigure(handle.pointer, X264_PARAMS_PARSE, name, value.orEmpty())
    }

    actual fun paramsPreset(handle: X264ParamsHandle, preset: String): Int {
        ensureInitialised()
        return webParamsConfigure(handle.pointer, X264_PARAMS_PRESET, preset, "")
    }

    actual fun paramsTune(handle: X264ParamsHandle, tune: String): Int {
        ensureInitialised()
        return webParamsConfigure(handle.pointer, X264_PARAMS_TUNE, tune, "")
    }

    actual fun paramsProfile(handle: X264ParamsHandle, profile: String): Int {
        ensureInitialised()
        return webParamsConfigure(handle.pointer, X264_PARAMS_PROFILE, profile, "")
    }

    actual fun paramsValidate(handle: X264ParamsHandle): Int {
        ensureInitialised()
        return webParamsValidate(handle.pointer)
    }

    actual fun paramsGetInt(handle: X264ParamsHandle, field: Int): Int {
        ensureInitialised()
        return webParamsGetInt(handle.pointer, field)
    }

    actual fun paramsSetLogLevel(handle: X264ParamsHandle, level: Int): Int {
        ensureInitialised()
        return webParamsSetLogLevel(handle.pointer, level)
    }

    actual fun encoderOpen(params: X264ParamsHandle): X264EncoderHandle? {
        ensureInitialised()
        val pointer = webEncoderOpen(params.pointer)
        if (pointer == 0) return null
        return X264EncoderHandle().also { it.pointer = pointer }
    }

    actual fun encoderClose(encoder: X264EncoderHandle) {
        ensureInitialised()
        webEncoderClose(encoder.pointer)
        encoder.pointer = 0
    }

    actual fun encoderParameters(encoder: X264EncoderHandle, params: X264ParamsHandle): Int {
        ensureInitialised()
        return webEncoderParameters(encoder.pointer, params.pointer)
    }

    actual fun encoderHeaders(encoder: X264EncoderHandle): ByteArray? {
        ensureInitialised()
        return parseWebResult(webEncoderHeaders(encoder.pointer), "header request")
    }

    actual fun encode(encoder: X264EncoderHandle, picture: X264Picture): ByteArray? {
        ensureInitialised()
        val payload = X264Packing.pack(picture)
        return parseWebResult(webEncode(encoder.pointer, payload), "encode")
    }

    actual fun flush(encoder: X264EncoderHandle): ByteArray? {
        ensureInitialised()
        return parseWebResult(webFlush(encoder.pointer), "flush")
    }
}
