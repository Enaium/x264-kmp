/*
 * C shim for the x264 Kotlin Multiplatform binding.
 *
 * The same implementation is compiled three ways:
 *   - into the static archive linked by every Kotlin/Native cinterop klib,
 *   - into the JNI shared library (x264_jni.c),
 *   - into the standalone WebAssembly module (x264_wasm_entry.c).
 *
 * All three expose this API, so argument validation and the packed output
 * format are identical on every platform.
 *
 * Conventions:
 *   - functions returning int return XK_OK / XK_BUFFERED on success and a
 *     negative code on failure;
 *   - `xk_output_blob()` returns a buffer owned by the encoder, valid until the
 *     next call that encodes or flushes; callers copy it if they keep it;
 *   - pictures are planar 8-bit YUV. x264 does not convert the input: the
 *     colour space passed to `xk_params_create()` (or to
 *     `xk_encode_planes()`) must match the caller's buffers.
 */
#ifndef X264_KMP_H
#define X264_KMP_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** First four bytes of the blob returned by `xk_output_blob()`. */
#define XK_OUTPUT_MAGIC 0x4F503458 /* "X4PO" little-endian */

/** Layout revision of the blob. */
#define XK_OUTPUT_VERSION 1

/** A picture (or header block) is available in the output buffer. */
#define XK_OK 1

/** The input was accepted but no picture is ready yet (or flushing is done). */
#define XK_BUFFERED 0

/** Generic failure; the encoder is usually unusable afterwards. */
#define XK_ERROR -1

/** x264 colour space values, exactly `X264_CSP_*`. */
#define XK_CSP_I400 0x0001
#define XK_CSP_I420 0x0002
#define XK_CSP_I422 0x0006
#define XK_CSP_I444 0x000C

/** Readable parameter fields, used by `xk_params_get_int()`. */
enum {
  XK_FIELD_WIDTH = 1,
  XK_FIELD_HEIGHT = 2,
  XK_FIELD_CHROMA_FORMAT = 3,
  XK_FIELD_BIT_DEPTH = 4,
  XK_FIELD_FPS_NUM = 5,
  XK_FIELD_FPS_DEN = 6,
  XK_FIELD_FRAME_THREADS = 7,
  XK_FIELD_LOG_LEVEL = 8,
  XK_FIELD_REPEAT_HEADERS = 10,
  XK_FIELD_KEYFRAME_MAX = 11,
};

typedef struct xk_params xk_params;
typedef struct xk_encoder xk_encoder;

/* ------------------------------------------------------------------ */
/* Version                                                            */
/* ------------------------------------------------------------------ */

/** x264 public API build (the value of `X264_BUILD`, e.g. 165). */
int xk_version_packed(void);

/** Human-readable x264 version, e.g. "0.165.x". */
const char *xk_version_string(void);

/** Same as `xk_version_packed()`; kept for the shared Kotlin contract. */
int xk_api_build(void);

/* ------------------------------------------------------------------ */
/* Parameters                                                         */
/* ------------------------------------------------------------------ */

/**
 * Creates a parameter block for a `width` x `height` source, initialised with
 * x264's defaults (preset "medium", 30 fps), a quiet log level and Annex-B
 * output enabled. `csp` is one of XK_CSP_*.
 */
xk_params *xk_params_create(int width, int height, int csp);

void xk_params_free(xk_params *params);

/** Applies a CLI-style option, e.g. `xk_params_parse(p, "crf", "28")`. */
int xk_params_parse(xk_params *params, const char *name, const char *value);

/** Applies a preset; resets the block to x264's defaults first. */
int xk_params_apply_preset(xk_params *params, const char *preset);

/** Applies a tune on top of the current preset. */
int xk_params_apply_tune(xk_params *params, const char *tune);

/** Restricts the block to an H.264 profile, e.g. "high". */
int xk_params_apply_profile(xk_params *params, const char *profile);

/** Returns 0 when width, height, colour space and frame rate are usable. */
int xk_params_validate(const xk_params *params);

int xk_params_get_int(const xk_params *params, int field);

int xk_params_set_log_level(xk_params *params, int level);

/* ------------------------------------------------------------------ */
/* Encoder                                                            */
/* ------------------------------------------------------------------ */

/** Opens an encoder from a snapshot of `params`; NULL on failure. */
xk_encoder *xk_encoder_open(const xk_params *params);

void xk_encoder_close(xk_encoder *encoder);

/** Copies the parameters x264 actually uses into `out`. */
int xk_encoder_parameters(const xk_encoder *encoder, xk_params *out);

/** Emits SPS/PPS/SEI into the output buffer without consuming a picture. */
int xk_encoder_headers(xk_encoder *encoder);

/**
 * Encodes one planar picture. `u`/`v` are NULL for XK_CSP_I400; strides are in
 * bytes. Returns XK_OK when a picture was produced, XK_BUFFERED when x264 is
 * still buffering (frame threads and B-frames) and a negative code on error.
 */
int xk_encode_planes(xk_encoder *encoder,
                     const uint8_t *y, const uint8_t *u, const uint8_t *v,
                     int stride_y, int stride_u, int stride_v,
                     int width, int height, int csp, int64_t pts);

/**
 * Encodes one picture from the packed packet the web bridge produces:
 * a 56-byte header (magic XK_INPUT_MAGIC, width, height, csp, three strides,
 * reserved, int64 pts, three plane offsets, reserved) followed by the planes.
 */
int xk_encode_packed(xk_encoder *encoder, const uint8_t *data, int size);

/** Drains delayed pictures. Returns XK_BUFFERED once the encoder is empty. */
int xk_flush(xk_encoder *encoder);

/** Number of NAL units in the last produced picture. */
int xk_output_nal_count(const xk_encoder *encoder);

/** NAL unit type (H.264 `nal_unit_type`) of one NAL in the last picture. */
int xk_output_nal_type(const xk_encoder *encoder, int index);

/** Payload of one NAL, without start code or length prefix. */
const uint8_t *xk_output_nal_data(const xk_encoder *encoder, int index, int *size);

/** Picture type of the last produced picture, a `X264_TYPE_*` value. */
int xk_output_slice_type(const xk_encoder *encoder);

int64_t xk_output_pts(const xk_encoder *encoder);

int64_t xk_output_dts(const xk_encoder *encoder);

/**
 * Serialises the last produced picture into the packed format below and
 * returns it; `size` receives the byte count. The buffer is reused by the next
 * encode/flush call.
 *
 * Layout (all integers little-endian):
 *   int32  magic (XK_OUTPUT_MAGIC)
 *   int32  version (XK_OUTPUT_VERSION)
 *   int32  nal_count
 *   int32  slice_type
 *   int64  pts
 *   int64  dts
 *   int32  payload_size
 *   int32  reserved
 *   int32  nal_types[nal_count]
 *   int32  nal_offsets[nal_count]   (relative to payload start)
 *   int32  nal_sizes[nal_count]
 *   uint8  payload[], 8-byte aligned start, NAL data concatenated
 */
const uint8_t *xk_output_blob(xk_encoder *encoder, int *size);

/* ------------------------------------------------------------------ */
/* Memory                                                             */
/* ------------------------------------------------------------------ */

void *xk_alloc(size_t size);

void xk_free(void *pointer);

#ifdef __cplusplus
}
#endif

#endif /* X264_KMP_H */
