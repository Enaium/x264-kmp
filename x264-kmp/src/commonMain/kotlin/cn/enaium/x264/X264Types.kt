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
 * Chroma subsampling of the encoder input, mirroring x264's `X264_CSP_*`.
 *
 * x264 down-converts nothing: the picture handed to [X264Encoder.encode] must
 * already match the colour space the encoder was opened with.
 */
enum class X264ChromaFormat(val value: Int) {
    /** Luma only. */
    I400(0x0001),

    /** 4:2:0, the camera and consumer video default. */
    I420(0x0002),

    /** 4:2:2. */
    I422(0x0006),

    /** 4:4:4. */
    I444(0x000C),
    ;

    internal fun chromaWidth(width: Int): Int =
        if (this == I444 || this == I400) width else (width + 1) / 2

    internal fun chromaHeight(height: Int): Int = when (this) {
        I400 -> 0
        I422 -> height
        else -> (height + 1) / 2
    }

    internal companion object {
        fun fromValue(value: Int): X264ChromaFormat =
            entries.firstOrNull { it.value == value } ?: error("Unknown x264 colour space $value")
    }
}

/**
 * Speed/efficiency preset, mirroring `x264_param_default_preset()`.
 *
 * [ULTRAFAST] is the real-time camera setting; [MEDIUM] is x264's default.
 */
enum class X264Preset(val value: String) {
    ULTRAFAST("ultrafast"),
    SUPERFAST("superfast"),
    VERYFAST("veryfast"),
    FASTER("faster"),
    FAST("fast"),
    MEDIUM("medium"),
    SLOW("slow"),
    SLOWER("slower"),
    VERYSLOW("veryslow"),
    PLACEBO("placebo"),
}

/** Tune, mirroring `x264_param_default_preset()`'s tune argument. */
enum class X264Tune(val value: String) {
    FILM("film"),
    ANIMATION("animation"),
    GRAIN("grain"),
    STILL_IMAGE("stillimage"),
    PSNR("psnr"),
    SSIM("ssim"),
    FAST_DECODE("fastdecode"),
    ZERO_LATENCY("zerolatency"),
}

/**
 * 8-bit H.264 profile accepted by this build, mirroring
 * `x264_param_apply_profile()`.
 *
 * Applying a profile restricts the encoder's feature set; the profile actually
 * written into the SPS is still derived from the final settings (an ultrafast
 * preset has none of the High-profile tools and therefore signals Baseline).
 */
enum class X264Profile(val value: String) {
    BASELINE("baseline"),
    MAIN("main"),
    HIGH("high"),
    HIGH_444("high444"),
}

/** x264 log verbosity, mirroring `X264_LOG_*`. */
enum class X264LogLevel(val value: Int) {
    NONE(-1),
    ERROR(0),
    WARNING(1),
    INFO(2),
    DEBUG(3),
    FULL(4),
}

/** H.264 NAL unit types x264 emits, see ITU-T H.264 Table 7-1. */
enum class X264NalType(val value: Int) {
    /** Coded slice of a non-IDR picture. */
    NON_IDR_SLICE(1),

    /** Coded slice data partition A. */
    PARTITION_A(2),

    /** Coded slice data partition B. */
    PARTITION_B(3),

    /** Coded slice data partition C. */
    PARTITION_C(4),

    /** Coded slice of an IDR picture. */
    IDR_SLICE(5),

    /** Supplemental enhancement information. */
    SEI(6),

    /** Sequence parameter set. */
    SPS(7),

    /** Picture parameter set. */
    PPS(8),

    /** Access unit delimiter. */
    AUD(9),

    /** End of sequence. */
    END_SEQUENCE(10),

    /** End of stream. */
    END_STREAM(11),

    /** Filler data. */
    FILLER(12),

    /** Sequence parameter set extension. */
    SPS_EXTENSION(13),

    /** Prefix NAL unit. */
    PREFIX_NAL(14),

    /** Subset sequence parameter set. */
    SUBSET_SPS(15),

    /** Coded slice of an auxiliary coded picture. */
    AUXILIARY_SLICE(19),

    /** Coded slice extension. */
    SLICE_EXTENSION(20),
    ;

    /** Random-access picture (an IDR slice in this binding's output). */
    val isIrap: Boolean get() = this == IDR_SLICE

    /** Coded slice NAL unit. */
    val isSlice: Boolean get() = value in 1..5 || value == 19 || value == 20

    val isParameterSet: Boolean get() = this == SPS || this == PPS

    internal companion object {
        fun fromValue(value: Int): X264NalType? = entries.firstOrNull { it.value == value }
    }
}

/** Encoded picture type, mirroring `X264_TYPE_*`. */
enum class X264SliceType(val value: Int) {
    AUTO(0),
    IDR(1),
    I(2),
    P(3),
    BREF(4),
    B(5),
    ;

    val isIntra: Boolean get() = this == IDR || this == I

    internal companion object {
        fun fromValue(value: Int): X264SliceType =
            entries.firstOrNull { it.value == value } ?: AUTO
    }
}

/** Readable parameter fields, mirroring `XK_FIELD_*` from the C shim. */
internal enum class X264Field(val value: Int) {
    WIDTH(1),
    HEIGHT(2),
    CHROMA_FORMAT(3),
    BIT_DEPTH(4),
    FPS_NUM(5),
    FPS_DEN(6),
    FRAME_THREADS(7),
    LOG_LEVEL(8),
    REPEAT_HEADERS(10),
    KEYFRAME_MAX(11),
}
