#
# Compiles x264 and the KMP C shim into one static archive and, optionally, the
# JNI shared library or the standalone WebAssembly module.
#
#   cmake -S jni -B build -DBUILD_JNI=ON               desktop JVM / Android JNI
#   cmake -S jni -B build                              static archive only
#   emcmake cmake -S jni -B build-wasm -DBUILD_WASM=ON WebAssembly
#
# x264 ships a hand-written Makefile, not a CMake project, so this file lists
# the sources it compiles. Assembly is disabled on every target: the binding
# runs the portable C kernels, which keeps one code path for all platforms and
# removes NASM and per-architecture assembler requirements from the build.
#
# X264_DIR points at the x264 checkout to compile; the default is the git
# submodule next to this directory.
#
cmake_minimum_required(VERSION 3.20)

project(x264_kmp C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(BUILD_JNI "Build the JNI shared library" OFF)
option(BUILD_WASM "Build the standalone WebAssembly module" OFF)

set(X264_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../x264" CACHE PATH "x264 checkout to build")
if(NOT EXISTS "${X264_DIR}/x264.h")
  message(FATAL_ERROR "x264 sources are missing at ${X264_DIR}")
endif()

file(STRINGS "${X264_DIR}/x264.h" XK_BUILD_LINE REGEX "^#define X264_BUILD ")
string(REGEX REPLACE "^#define X264_BUILD ([0-9]+).*" "\\1" XK_X264_BUILD "${XK_BUILD_LINE}")

# ---------------------------------------------------------------------------
# Target feature set. x264 is driven by a generated config.h; every feature is
# spelled out so a target never inherits the host's build configuration.
# ---------------------------------------------------------------------------
set(XK_HAVE_THREAD 1)
set(XK_HAVE_POSIXTHREAD 0)
set(XK_HAVE_WIN32THREAD 0)
set(XK_HAVE_STRTOK_R 1)
set(XK_HAVE_CLOCK_GETTIME 1)
set(XK_HAVE_SYSCONF 1)
set(XK_HAVE_MMAP 1)
set(XK_STACK_ALIGNMENT 16)
set(XK_SYS_DEFINE "")
set(XK_ARCH_DEFINE "")

if(EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  # No pthreads and no POSIX process information in the standalone module;
  # encoder.c then forces i_threads = 1.
  set(XK_HAVE_THREAD 0)
  set(XK_HAVE_SYSCONF 0)
  set(XK_HAVE_MMAP 0)
elseif(WIN32)
  set(XK_HAVE_POSIXTHREAD 0)
  set(XK_HAVE_WIN32THREAD 1)
  set(XK_HAVE_STRTOK_R 0)
  set(XK_HAVE_CLOCK_GETTIME 0)
  set(XK_HAVE_SYSCONF 0)
  set(XK_HAVE_MMAP 0)
  set(XK_SYS_DEFINE "#define SYS_WINDOWS 1")
elseif(APPLE)
  set(XK_HAVE_POSIXTHREAD 1)
  set(XK_SYS_DEFINE "#define SYS_MACOSX 1")
else()
  set(XK_HAVE_POSIXTHREAD 1)
  set(XK_SYS_DEFINE "#define SYS_LINUX 1")
endif()

# The architecture macro only selects cpu.c's feature naming table; all SIMD
# implementations are disabled above, so the portable C paths are used.
if(NOT EMSCRIPTEN AND NOT CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  string(TOLOWER "${CMAKE_SYSTEM_PROCESSOR}" XK_PROCESSOR)
  if(XK_PROCESSOR MATCHES "^(aarch64|arm64|arm64e)$")
    set(XK_ARCH_DEFINE "#define ARCH_AARCH64 1")
  elseif(XK_PROCESSOR MATCHES "^(x86_64|amd64)$")
    set(XK_ARCH_DEFINE "#define ARCH_X86_64 1")
  elseif(XK_PROCESSOR MATCHES "^(i[3-6]86|x86)$")
    set(XK_ARCH_DEFINE "#define ARCH_X86 1")
  endif()
endif()

configure_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/config.h.in"
  "${CMAKE_CURRENT_BINARY_DIR}/generated/config.h"
)
configure_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/x264_config.h.in"
  "${CMAKE_CURRENT_BINARY_DIR}/generated/x264_config.h"
)

set(XK_SOURCES
  "${X264_DIR}/common/osdep.c"
  "${X264_DIR}/common/base.c"
  "${X264_DIR}/common/cpu.c"
  "${X264_DIR}/common/tables.c"
  "${X264_DIR}/common/bitstream.c"
  "${X264_DIR}/common/mc.c"
  "${X264_DIR}/common/predict.c"
  "${X264_DIR}/common/pixel.c"
  "${X264_DIR}/common/macroblock.c"
  "${X264_DIR}/common/frame.c"
  "${X264_DIR}/common/dct.c"
  "${X264_DIR}/common/cabac.c"
  "${X264_DIR}/common/common.c"
  "${X264_DIR}/common/rectangle.c"
  "${X264_DIR}/common/set.c"
  "${X264_DIR}/common/quant.c"
  "${X264_DIR}/common/deblock.c"
  "${X264_DIR}/common/vlc.c"
  "${X264_DIR}/common/mvpred.c"
  "${X264_DIR}/encoder/api.c"
  "${X264_DIR}/encoder/encoder.c"
  "${X264_DIR}/encoder/analyse.c"
  "${X264_DIR}/encoder/me.c"
  "${X264_DIR}/encoder/ratecontrol.c"
  "${X264_DIR}/encoder/set.c"
  "${X264_DIR}/encoder/macroblock.c"
  "${X264_DIR}/encoder/cabac.c"
  "${X264_DIR}/encoder/cavlc.c"
  "${X264_DIR}/encoder/lookahead.c"
  "${CMAKE_CURRENT_SOURCE_DIR}/x264_kmp.c"
)

if(XK_HAVE_THREAD)
  list(APPEND XK_SOURCES "${X264_DIR}/common/threadpool.c")
endif()
if(XK_HAVE_WIN32THREAD)
  list(APPEND XK_SOURCES "${X264_DIR}/common/win32thread.c")
endif()

add_library(x264-static STATIC ${XK_SOURCES})
target_include_directories(x264-static
  PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include"
  PRIVATE
    "${X264_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
)
target_compile_definitions(x264-static PRIVATE BIT_DEPTH=8)
if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
  target_compile_options(x264-static PRIVATE -Wno-unused-function)
endif()

# sched_getaffinity()/cpu_set_t are GNU extensions: glibc and bionic only
# declare them with _GNU_SOURCE. Defining it for cpu.c alone keeps the C23
# strtol/sscanf redirection out of the rest of x264, which Kotlin/Native's older
# Linux sysroot does not provide (__isoc23_* symbols).
if(CMAKE_SYSTEM_NAME STREQUAL "Linux" OR ANDROID)
  set_source_files_properties("${X264_DIR}/common/cpu.c"
    TARGET_DIRECTORY x264-static
    PROPERTIES COMPILE_DEFINITIONS "_GNU_SOURCE")
endif()

# The archive name is what the cinterop `-staticLibrary` flag and the JNI link
# line expect: libx264_kmp.a on Unix/MinGW.
set_target_properties(x264-static PROPERTIES OUTPUT_NAME x264_kmp)

if(UNIX)
  find_package(Threads REQUIRED)
  target_link_libraries(x264-static PUBLIC Threads::Threads)
endif()

if(BUILD_JNI)
  add_library(x264_jni SHARED "${CMAKE_CURRENT_SOURCE_DIR}/x264_jni.c")
  target_link_libraries(x264_jni PRIVATE x264-static)
  set_target_properties(x264_jni PROPERTIES OUTPUT_NAME x264_jni)

  if(WIN32)
    # MinGW names shared libraries lib*.dll by default; the Kotlin loader uses
    # the unprefixed x264_jni.dll. The library is pure C: only the MinGW
    # runtime itself has to be linked statically.
    set_target_properties(x264_jni PROPERTIES PREFIX "")
    target_link_options(x264_jni PRIVATE -static -static-libgcc)
  endif()

  if(ANDROID)
    find_library(log-lib log)
    if(log-lib)
      target_link_libraries(x264_jni PRIVATE ${log-lib})
    endif()
    # 16 KB page-size devices (Android 15+).
    target_link_options(x264_jni PRIVATE
      "-Wl,-z,max-page-size=16384"
      "-Wl,-z,common-page-size=16384"
    )
  else()
    # Derive the headers from JAVA_HOME first: FindJNI refuses to configure
    # when the target platform differs from the host (cross builds for MinGW).
    if(NOT JNI_INCLUDE_DIR AND DEFINED ENV{JAVA_HOME})
      set(JNI_INCLUDE_DIR "$ENV{JAVA_HOME}/include")
      if(WIN32)
        set(JNI_INCLUDE_DIR_PLATFORM "$ENV{JAVA_HOME}/include/win32")
      elseif(APPLE)
        set(JNI_INCLUDE_DIR_PLATFORM "$ENV{JAVA_HOME}/include/darwin")
      else()
        set(JNI_INCLUDE_DIR_PLATFORM "$ENV{JAVA_HOME}/include/linux")
      endif()
    endif()
    if(NOT JNI_INCLUDE_DIR)
      find_package(JNI REQUIRED)
    endif()
    if(JNI_INCLUDE_DIR)
      target_include_directories(x264_jni PRIVATE "${JNI_INCLUDE_DIR}")
    endif()
    if(JNI_INCLUDE_DIR_PLATFORM)
      target_include_directories(x264_jni PRIVATE "${JNI_INCLUDE_DIR_PLATFORM}")
    endif()
    if(JNI_INCLUDE_DIRS)
      target_include_directories(x264_jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
  endif()
endif()

if(BUILD_WASM OR EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  add_executable(x264_wasm "${CMAKE_CURRENT_SOURCE_DIR}/x264_wasm_entry.c")
  target_link_libraries(x264_wasm PRIVATE x264-static)
  set_target_properties(x264_wasm PROPERTIES SUFFIX ".wasm")
  target_link_options(x264_wasm PRIVATE
    "-sSTANDALONE_WASM=1"
    "-sALLOW_MEMORY_GROWTH=1"
    "-sINITIAL_MEMORY=67108864"
    # x264's encoder stack frames are large; the Emscripten default of 64 KiB is
    # not enough for one 1080p frame.
    "-sSTACK_SIZE=8388608"
    "-sERROR_ON_UNDEFINED_SYMBOLS=1"
    "-sEXPORTED_FUNCTIONS=_xk_api_build,_xk_version_packed,_xk_version_string,_xk_params_create,_xk_params_free,_xk_params_parse,_xk_params_apply_preset,_xk_params_apply_tune,_xk_params_apply_profile,_xk_params_validate,_xk_params_get_int,_xk_params_set_log_level,_xk_encoder_parameters,_xk_encoder_open,_xk_encoder_close,_xk_encoder_headers,_xk_encode_planes,_xk_encode_packed,_xk_flush,_xk_output_nal_count,_xk_output_nal_type,_xk_output_nal_data,_xk_output_slice_type,_xk_output_pts,_xk_output_dts,_xk_output_blob,_xk_alloc,_xk_free,_malloc,_free"
    "--no-entry"
  )
endif()
