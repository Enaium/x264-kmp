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

Binding version: **0.165.0.1** (x264 API build 165, `X264_POINTVER` `0.165.x`).

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

The standalone module, the JS glue and the licence are also published as the
`@enaium/x264-kmp` npm package (`:x264-kmp:assembleWasmNpm` stages it in
`build/npm`, `:x264-kmp:publishWasmNpm` publishes it). npm only accepts
three-component semver, so `0.165.0.1` is published as `0.165.0-1` under
the `latest` tag. Plain JavaScript apps instantiate it without Kotlin:

```js
import { initX264Kmp } from "@enaium/x264-kmp";

const module = await initX264Kmp();
console.log(module.versionString());
```

The npm token is read from `npm.publish.key` in `~/.gradle/gradle.properties`
(or `-Pnpm.publish.key=<token>`); CI reads the `NPM_PUBLISH_KEY` secret.

This build is 8-bit only (`X264_BIT_DEPTH 8`). 10-bit input is not accepted.

## Using the library

```kotlin
dependencies {
    implementation("cn.enaium.x264:x264-kmp:0.165.0.1")
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

The examples share one SDL3 + Dear ImGui app in `examples/common` (targets:
`jvm`, macOS/Linux/Windows native, Android JVM, Android Native and `wasmJs`).
Camera frames come from [camera-kmp](https://github.com/Enaium/camera-kmp):
Camera2 on Android, Camera2 NDK and V4L2 on Android Native,
AVFoundation/V4L2/Media Foundation on the desktops and `getUserMedia` in the
browser, so the same code runs on every target instead of an Android-only
camera implementation.

The pipeline is camera-kmp I420 -> x264 `ultrafast` + `zerolatency` encode.
Decoding uses the platform API where one exists: MediaCodec on Android (JVM
and native) and WebCodecs in the browser; the desktop targets fall back to
OpenH264 (`cn.enaium.openh264:openh264-kmp`). The UI shows the camera picture
next to the decoded round trip with frame, byte and bitrate counters. A
deterministic test pattern is used only until the camera delivers its first
frame (or when no camera exists), so the preview never alternates between the
test pattern and the camera.

The left-hand panel of the UI edits the capture parameters: image source
(camera or a generated test pattern), resolution (320x240 through 1920x1080),
clockwise rotation (0/90/180/270), front/back lens and horizontal mirroring.
Rotation applies to both the preview and the output; the mirror option applies
only to the encoded/decoded output, while the camera preview stays unmirrored. Changing one restarts the camera session and
recreates the encoder/decoder chain for the new dimensions.

`examples/common` is also the wasmJs browser host: its Emscripten runtimes
(sdl-kmp, imgui-kmp and camera-kmp) are installed as npm packages and served
next to the Kotlin module, and `requestAnimationFrame` drives the shared loop.

The two Android host modules are thin wrappers around the shared code:

- `examples/android-jvm` - an Android app whose `SDLActivity.main()` calls the
  shared `runExample()`.
- `examples/android-native` - an Android app that packages the `libmain.so`
  built by `examples/common`; it carries no camera or UI code of its own.

```bash
# desktop entry of the shared module
./gradlew :examples:common:linkDebugExecutableMacosArm64
./examples/common/build/bin/macosArm64/debugExecutable/common.kexe

./gradlew :examples:android-jvm:assembleDebug
./gradlew -PandroidAbis=arm64-v8a :examples:android-native:assembleDebug

./gradlew :examples:common:wasmJsBrowserDistribution
python3 -m http.server 8080 --directory examples/common/build/dist/wasmJs/productionExecutable
# open http://localhost:8080/ - serve over HTTPS or localhost for the camera
```

The SDL3 and imgui-kmp APIs are published on Maven Central
(`cn.enaium.sdl:sdl-kmp:3.4.18.2`, `cn.enaium.imgui:imgui-kmp:1.93.0.2`); the
browser runtimes are `@enaium/sdl-kmp@3.4.18-2`, `@enaium/imgui-kmp@1.93.0-2`
and `@enaium/camera-kmp@1.0.0`.

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
