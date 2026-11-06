# x264-kmp

Kotlin Multiplatform bindings for [x264](https://code.videolan.org/videolan/x264):
H.264 encoding on JVM, Android, Kotlin/Native and the web.

`x264` is included as a Git submodule and compiled from source for every
target. One C ABI ([`jni/include/x264_kmp.h`](jni/include/x264_kmp.h)) drives
the JNI bridge, the Kotlin/Native cinterop and the WebAssembly build, so every
platform shares the same parameter handling, input validation and result
format. x264's assembly kernels are disabled: the portable C implementation
runs everywhere, which keeps a single code path across x86, ARM, Android, MinGW
and WebAssembly.

Binding version: **1.0.0** (x264 API build 165, `X264_POINTVER` `0.165.x`).

## Supported targets

| Platform | Kotlin target | Native artifact |
|---|---|---|
| JVM | `jvm` | `x264-kmp-jni-jvm-{linux,darwin}-{x86_64,aarch64}`, `x264-kmp-jni-jvm-windows-x86_64` |
| Android | `android` | `x264-kmp` AAR with `libx264_jni.so` for every ABI |
| Android Native | `androidNativeArm64`, `androidNativeArm32`, `androidNativeX64`, `androidNativeX86` | static archive embedded in the cinterop klib |
| Linux | `linuxX64`, `linuxArm64` | static archive embedded in the cinterop klib |
| Windows | `mingwX64` | static archive embedded in the cinterop klib |
| macOS | `macosX64`, `macosArm64` | static archive embedded in the cinterop klib |
| iOS | `iosX64`, `iosArm64`, `iosSimulatorArm64` | static archive embedded in the cinterop klib |
| tvOS | `tvosArm64`, `tvosSimulatorArm64` | static archive embedded in the cinterop klib |
| watchOS | `watchosArm64`, `watchosSimulatorArm64`, `watchosDeviceArm64` | static archive embedded in the cinterop klib |
| Kotlin/JS | `js` | embedded WebAssembly module |
| Kotlin/Wasm | `wasmJs` | embedded WebAssembly module |

The web targets compile x264 to a standalone `.wasm` module with Emscripten,
embed the binary into the published klib and instantiate it synchronously
through a small JS bridge. No resource lookup or async initialization is
required at runtime. Emscripten has no pthreads, so that build sets
`HAVE_THREAD=0`; `encoder.c` then forces a single frame thread and the shim
also clears sliced threads and the lookahead pool. Every other target uses
x264's normal thread pool.

This build is 8-bit only (`X264_BIT_DEPTH 8`). 10-bit input is not accepted.

## Using the library

```kotlin
dependencies {
    implementation("cn.enaium.x264:x264-kmp:1.0.0")
}
```

The Android AAR and the JVM artifacts contain the native libraries. JVM
consumers automatically receive the per-OS/arch JNI artifacts and
`NativeLoader` extracts the matching one from the classpath.

### Encoding

```kotlin
import cn.enaium.x264.*

val param = X264Param.create(width = 1280, height = 720).apply {
    preset(X264Preset.ULTRAFAST)     // real-time camera setting
    tune(X264Tune.ZERO_LATENCY)
    crf(28.0)                        // 0 = lossless, 51 = worst
    keyframeMax = 30
    logLevel = X264LogLevel.NONE
}

X264Encoder.open(param).use { encoder ->
    // SPS/PPS, required as MediaCodec's csd-0 or by a muxer.
    val parameterSets = encoder.headers().toAnnexB()

    val picture = X264Picture.i420(y, u, v, width, height, pts = presentationTimeUs)
    encoder.encode(picture)?.let { frame ->
        // frame.nals exposes every NAL unit; frame.toAnnexB() adds start codes.
        sink.write(frame.toAnnexB())
    }

    // B-frames and the lookahead delay pictures; drain them before closing.
    encoder.flush().forEach { sink.write(it.toAnnexB()) }
}
```

Options are reachable by their `x264_param_parse` names through `set`, so the
names and value syntax are exactly the ones `x264 --help` documents:

```kotlin
param.set("crf", 24)
param.set("bframes", 6)
param.set("aq-mode", 3)
param.set("repeat-headers", true)
param.set("threads", 4)
```

Presets, tunes and profiles are typed shortcuts (`preset`, `tune`, `profile`)
because x264 applies them by resetting the block to its defaults; the shim
re-applies the source geometry, frame rate, log level and Annex-B setting so a
preset never changes the picture size behind the caller's back.
`X264Param.set("preset", ...)` is not an x264 option and is rejected.

### Frames, NAL units and timestamps

`X264Frame` carries the NAL units of one encoded picture plus the presentation
and decoding timestamps x264 echoed back. `X264Nal.data` starts with the
one-byte H.264 NAL header; x264's own start codes or length prefixes are
stripped by the binding, so `toAnnexB()` emits exactly one start code per NAL.
That matters for permissive parsers like ffmpeg (which tolerate doubled start
codes) and strict consumers like MediaCodec (which do not).

`X264Encoder.encode` returns `null` while x264 buffers input for frame
threading or B-frame reordering. `flush` returns every remaining picture and
must be called once after the last input frame; it loops internally until x264
reports no delayed frames, because one `x264_encoder_encode(NULL)` call can
still return empty when several frame-thread contexts are active.

## Examples

`examples/android-jvm` is a Compose loopback: CameraX → x264 → MediaCodec → `TextureView`.

1. CameraX `ImageAnalysis` provides `YUV_420_888` frames.
2. [`cn.enaium.libyuv:libyuv-kmp:1.2.0`](https://github.com/Enaium/libyuv-kmp)
   turns the camera's (often semi-planar, often padded) planes into tightly
   packed I420 planes through the `ImageProxy.toI420()` extension.
3. `X264Encoder` encodes with the `ultrafast` + `zerolatency` preset.
4. A `video/avc` `MediaCodec` decoder renders the Annex-B access units straight
   to the preview `Surface`.

```bash
./gradlew :examples:android-jvm:assembleDebug
```

`examples/android-native` is the same loop written for Kotlin/Native with SDL3
and Dear ImGui ([sdl-kmp](https://github.com/Enaium/sdl-kmp) `3.4.18.1`,
[imgui-kmp](https://github.com/Enaium/imgui-kmp) `1.93.0.1`):

1. Camera2 NDK (`AImageReader`) captures `YUV_420_888`, converted with libyuv.
2. `X264Encoder` runs in `libmain.so`, linked statically into the SDL activity.
3. The NDK `AMediaCodec` C API decodes `video/avc` to YUV buffers.
4. libyuv converts the decoded frame to RGBA and ImGui draws it.

The module also builds a macOS executable that runs the same UI and the x264
half of the loop (MediaCodec is Android-only), which is how the example is
verified without a device:

```bash
./gradlew :examples:android-native:linkDebugExecutableMacosArm64
X264_KMP_FRAMES=40 ./examples/android-native/build/bin/macosArm64/debugExecutable/android-native.kexe

# APK; narrow the ABIs on a host that cannot link all four
./gradlew -PandroidAbis=arm64-v8a :examples:android-native:android:assembleDebug
```

### Browser (getUserMedia → H.264 → WebCodecs)

`examples/browser` is a Kotlin/JS page. It captures the camera with
`navigator.mediaDevices.getUserMedia()`, converts every frame to I420 and
encodes it with `X264Encoder` (the same embedded WebAssembly module as the
published js artifact). The Annex-B output is handed to a WebCodecs
`VideoDecoder`, which paints the decoded stream on a second canvas. H.264
decoding is available in every Chromium/Firefox/Safari build that implements
WebCodecs; the page detects a missing `VideoDecoder` and reports it instead of
failing. `getUserMedia` needs a secure context, so serve the page over
`localhost`:

```bash
./gradlew :examples:browser:jsBrowserDistribution
python3 -m http.server 8080 --directory examples/browser/build/dist/js/productionExecutable
# open http://localhost:8080/
```

## Building from source

```bash
git submodule update --init --recursive
./gradlew :x264-kmp:jvmTest                 # JNI + tests
./gradlew :x264-kmp:macosArm64Test          # cinterop + static archive
./gradlew :x264-kmp:jsNodeTest              # Emscripten module in Node
./gradlew :examples:android-jvm:assembleDebug
```

Native targets are built through CMake, which compiles the x264 source list
directly (`jni/CMakeLists.txt`): no NASM, no per-architecture assembler and no
C++ runtime are needed. The web build compiles the submodule in place and
embeds `x264_wasm.wasm` into the published klib.

## Licence

x264 is licensed under the GPL v2 or later (or a commercial licence from
x264's authors). Linking it into an application makes the resulting work subject to
the GPL unless a commercial x264 licence is obtained. The bindings themselves
are released under the GPL-2.0-or-later.
