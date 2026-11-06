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

/** Version information of the bundled x264 build and of this binding. */
object X264 {
    /** Version of the Kotlin bindings, independent of the x264 API build. */
    const val VERSION: String = "1.0.0"

    /** The upstream x264 point version this binding ships, e.g. `"0.165.x"`. */
    val libraryVersion: String by lazy { X264Native.versionString() }

    /** x264 public API build (the value of `X264_BUILD`, e.g. 165). */
    val libraryVersionPacked: Int by lazy { X264Native.versionPacked() }

    /** x264 public API build; equal to [libraryVersionPacked] for x264. */
    val apiBuild: Int by lazy { X264Native.apiBuild() }

    /** 8-bit build: [X264Param.bitDepth] is always 8. */
    const val BIT_DEPTH: Int = 8

    override fun toString(): String = "x264 $libraryVersion (api $apiBuild), bindings $VERSION"
}

/**
 * An x264 parameter block.
 *
 * Options understood by `x264_param_parse` are set by their CLI names through
 * [set], so the binding does not have to mirror the (very large) `x264_param`
 * struct. [preset], [tune], [profile] and [frameRate] are typed shortcuts.
 *
 * The block is consulted by [X264Encoder.open]; afterwards the encoder owns a
 * copy and this block may be closed or changed freely.
 *
 * Instances are not thread-safe: configure them on one thread, then hand the
 * immutable encoder to the worker thread.
 */
class X264Param internal constructor(internal val handle: X264ParamsHandle) : AutoCloseable {
    private var closed = false

    val width: Int get() = getInt(X264Field.WIDTH)

    val height: Int get() = getInt(X264Field.HEIGHT)

    val chromaFormat: X264ChromaFormat
        get() = X264ChromaFormat.fromValue(getInt(X264Field.CHROMA_FORMAT))

    /** Internal bit depth of this build; always [X264.BIT_DEPTH]. */
    val bitDepth: Int get() = getInt(X264Field.BIT_DEPTH)

    var frameRateNum: Int
        get() = getInt(X264Field.FPS_NUM)
        set(value) {
            set("fps", "$value/$frameRateDen")
        }

    var frameRateDen: Int
        get() = getInt(X264Field.FPS_DEN)
        set(value) {
            set("fps", "$frameRateNum/$value")
        }

    /** Configured frame threads; 0 means "auto-detect when the encoder opens". */
    val frameThreads: Int get() = getInt(X264Field.FRAME_THREADS)

    var logLevel: X264LogLevel
        get() = X264LogLevel.entries.firstOrNull { it.value == getInt(X264Field.LOG_LEVEL) }
            ?: X264LogLevel.INFO
        set(value) {
            checkOpen()
            val result = X264Native.paramsSetLogLevel(handle, value.value)
            if (result < 0) {
                throw X264Exception("x264 rejected log level ${value.value} (code $result)")
            }
        }

    /** Repeat SPS/PPS before every keyframe, useful for stream joins. */
    var repeatHeaders: Boolean
        get() = getInt(X264Field.REPEAT_HEADERS) != 0
        set(value) {
            set("repeat-headers", value)
        }

    /** Maximum keyframe distance in frames. */
    var keyframeMax: Int
        get() = getInt(X264Field.KEYFRAME_MAX)
        set(value) {
            set("keyint", value)
        }

    /** Sets `name` to an integer value, e.g. `set("crf", 28)`. */
    fun set(name: String, value: Int): X264Param = apply(name, value.toString())

    /** Sets `name` to a boolean value, e.g. `set("repeat-headers", true)`. */
    fun set(name: String, value: Boolean): X264Param = apply(name, if (value) "1" else "0")

    /** Sets `name` to a textual value, e.g. `set("tune", "zerolatency")`. */
    fun set(name: String, value: String): X264Param = apply(name, value)

    /** Applies a speed/efficiency preset. Resets the block to x264's defaults. */
    fun preset(preset: X264Preset): X264Param {
        checkOpen()
        val result = X264Native.paramsPreset(handle, preset.value)
        if (result < 0) {
            throw X264Exception("x264 rejected preset '${preset.value}' (code $result)")
        }
        return this
    }

    /** Applies a tune; keeps the current preset. */
    fun tune(tune: X264Tune): X264Param {
        checkOpen()
        val result = X264Native.paramsTune(handle, tune.value)
        if (result < 0) {
            throw X264Exception("x264 rejected tune '${tune.value}' (code $result)")
        }
        return this
    }

    /** Applies an 8-bit profile. */
    fun profile(profile: X264Profile): X264Param {
        checkOpen()
        val result = X264Native.paramsProfile(handle, profile.value)
        if (result < 0) {
            throw X264Exception("x264 rejected profile '${profile.value}' (code $result)")
        }
        return this
    }

    /** Applies a profile by its raw x264 name. */
    fun profile(name: String): X264Param {
        checkOpen()
        val result = X264Native.paramsProfile(handle, name)
        if (result < 0) {
            throw X264Exception("x264 rejected profile '$name' (code $result)")
        }
        return this
    }

    /** Sets the source frame rate. */
    fun frameRate(numerator: Int, denominator: Int = 1): X264Param = set("fps", "$numerator/$denominator")

    /**
     * Constant-rate-factor mode, the recommended quality mode: 0 is lossless,
     * 28 is roughly "visually good", 51 the worst quality.
     */
    fun crf(value: Double): X264Param = set("crf", value.toString())

    /** Constant-bitrate target in kbit/s (switches x264 to ABR mode). */
    fun bitrate(kbitsPerSecond: Int): X264Param = set("bitrate", kbitsPerSecond)

    /** Sanity check of the fields x264 validates before opening an encoder. */
    fun validate(): Boolean {
        checkOpen()
        return X264Native.paramsValidate(handle) == 0
    }

    internal fun getInt(field: X264Field): Int = X264Native.paramsGetInt(handle, field.value)

    override fun close() {
        if (!closed) {
            closed = true
            X264Native.paramsFree(handle)
        }
    }

    override fun toString(): String =
        "X264Param(${width}x$height $chromaFormat, bitDepth=$bitDepth, threads=$frameThreads)"

    private fun apply(name: String, value: String): X264Param {
        checkOpen()
        val result = X264Native.paramsParse(handle, name, value)
        if (result < 0) {
            throw X264Exception("x264 rejected option '$name'='$value' (code $result)")
        }
        return this
    }

    private fun checkOpen() {
        check(!closed) { "X264Param is closed" }
    }

    companion object {
        /**
         * Creates a parameter block for a `width` x `height` source, initialised
         * with x264's defaults, the [preset] and 30 fps.
         */
        fun create(
            width: Int,
            height: Int,
            chromaFormat: X264ChromaFormat = X264ChromaFormat.I420,
            preset: X264Preset = X264Preset.MEDIUM,
        ): X264Param {
            require(width > 0 && height > 0) { "size must be positive, got ${width}x$height" }
            val handle = X264Native.paramsCreate(width, height, chromaFormat.value)
                ?: throw X264Exception("x264 could not allocate ${width}x$height $chromaFormat parameters")
            val param = X264Param(handle)
            try {
                if (preset != X264Preset.MEDIUM) {
                    param.preset(preset)
                }
                if (chromaFormat == X264ChromaFormat.I420 && (width % 2 != 0 || height % 2 != 0)) {
                    throw X264Exception("4:2:0 requires even dimensions, got ${width}x$height")
                }
            } catch (e: Throwable) {
                param.close()
                throw e
            }
            return param
        }
    }
}
