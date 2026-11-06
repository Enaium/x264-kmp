/*
 * C shim for the x264 Kotlin Multiplatform binding. See x264_kmp.h for the
 * contract.
 *
 * Only the public libx264 API is used, so the same translation unit compiles
 * for every target. The shim deliberately owns the packed output format: the
 * Kotlin side then has a single parser for JNI, cinterop and WebAssembly.
 */
#include "x264_kmp.h"

#include <stdlib.h>
#include <string.h>

#include "x264.h"

#define XK_PRESET_MAX 32
#define XK_TUNE_MAX 32

struct xk_params {
  x264_param_t param;
  char preset[XK_PRESET_MAX];
  char tune[XK_TUNE_MAX];
};

struct xk_encoder {
  x264_t *enc;
  xk_params params;

  x264_picture_t pic_out;
  x264_nal_t *nals;
  int nal_count;

  /* Last picture described by xk_output_nal_*(). */
  int out_nal_count;
  int out_slice_type;
  int64_t out_pts;
  int64_t out_dts;

  uint8_t *blob;
  int blob_capacity;
};

/* ------------------------------------------------------------------------- */
/* Memory                                                                     */
/* ------------------------------------------------------------------------- */

void *xk_alloc(size_t size) {
  /* malloc(0) may return NULL, which the callers would report as OOM. */
  return malloc(size ? size : 1);
}

/* ------------------------------------------------------------------------- */
/* Version                                                                    */
/* ------------------------------------------------------------------------- */

int xk_version_packed(void) { return X264_BUILD; }

const char *xk_version_string(void) { return X264_POINTVER; }

int xk_api_build(void) { return X264_BUILD; }

void xk_free(void *pointer) { free(pointer); }

/* ------------------------------------------------------------------------- */
/* Parameters                                                                 */
/* ------------------------------------------------------------------------- */

/* Values the binding manages itself and re-applies after x264 resets a
 * parameter block to its defaults (preset, tune). Source geometry and rate
 * are not encoder tuning: a preset must not silently change them. */
typedef struct xk_managed {
  int width;
  int height;
  int csp;
  int fps_num;
  int fps_den;
  int log_level;
  int annexb;
  int repeat_headers;
} xk_managed;

static xk_managed xk_params_save(const xk_params *params) {
  xk_managed managed;
  managed.width = params->param.i_width;
  managed.height = params->param.i_height;
  managed.csp = params->param.i_csp;
  managed.fps_num = params->param.i_fps_num;
  managed.fps_den = params->param.i_fps_den;
  managed.log_level = params->param.i_log_level;
  managed.annexb = params->param.b_annexb;
  managed.repeat_headers = params->param.b_repeat_headers;
  return managed;
}

static void xk_params_restore(xk_params *params, const xk_managed *managed) {
  params->param.i_width = managed->width;
  params->param.i_height = managed->height;
  params->param.i_csp = managed->csp;
  params->param.i_fps_num = managed->fps_num;
  params->param.i_fps_den = managed->fps_den;
  params->param.i_log_level = managed->log_level;
  params->param.b_annexb = managed->annexb;
  params->param.b_repeat_headers = managed->repeat_headers;
}

xk_params *xk_params_create(int width, int height, int csp) {
  xk_params *params = calloc(1, sizeof(*params));
  if (!params) return NULL;

  x264_param_default(&params->param);
  params->param.i_width = width;
  params->param.i_height = height;
  params->param.i_csp = csp;
  params->param.i_fps_num = 30;
  params->param.i_fps_den = 1;
  /* Keep the library quiet by default; the example application opts in to
   * warnings through X264Param.logLevel. */
  params->param.i_log_level = X264_LOG_ERROR;
  /* The blob strips start codes either way; keep x264's default Annex-B
   * output because it is the best-tested path. */
  params->param.b_annexb = 1;
  params->param.b_repeat_headers = 1;
  params->preset[0] = '\0';
  params->tune[0] = '\0';
  return params;
}

void xk_params_free(xk_params *params) {
  if (!params) return;
  x264_param_cleanup(&params->param);
  free(params);
}

int xk_params_parse(xk_params *params, const char *name, const char *value) {
  if (!params || !name) return XK_ERROR;
  /* x264_param_parse() dereferences the value; a null value means "enable". */
  return x264_param_parse(&params->param, name, value ? value : "1");
}

/*
 * x264 has no public "apply preset without resetting" entry point, so the shim
 * keeps the requested preset/tune pair and rebuilds the block from x264's
 * defaults for that pair. Failing is atomic: the previous block is restored.
 */
static int xk_params_apply(xk_params *params, const char *preset, const char *tune) {
  x264_param_t previous = params->param;
  xk_managed managed = xk_params_save(params);
  char previous_preset[XK_PRESET_MAX];
  char previous_tune[XK_TUNE_MAX];
  memcpy(previous_preset, params->preset, XK_PRESET_MAX);
  memcpy(previous_tune, params->tune, XK_TUNE_MAX);

  int result = x264_param_default_preset(&params->param, preset, tune);
  if (result < 0) {
    params->param = previous;
    memcpy(params->preset, previous_preset, XK_PRESET_MAX);
    memcpy(params->tune, previous_tune, XK_TUNE_MAX);
    return result;
  }
  xk_params_restore(params, &managed);
  return 0;
}

static void xk_copy_string(char *destination, size_t size, const char *source) {
  size_t length = strlen(source);
  if (length >= size) length = size - 1;
  memcpy(destination, source, length);
  destination[length] = '\0';
}

int xk_params_apply_preset(xk_params *params, const char *preset) {
  if (!params || !preset) return XK_ERROR;
  char tune[XK_TUNE_MAX];
  memcpy(tune, params->tune, XK_TUNE_MAX);
  int result = xk_params_apply(params, preset, tune[0] ? tune : NULL);
  if (result == 0) xk_copy_string(params->preset, XK_PRESET_MAX, preset);
  return result;
}

int xk_params_apply_tune(xk_params *params, const char *tune) {
  if (!params || !tune) return XK_ERROR;
  const char *preset = params->preset[0] ? params->preset : NULL;
  int result = xk_params_apply(params, preset, tune);
  if (result == 0) xk_copy_string(params->tune, XK_TUNE_MAX, tune);
  return result;
}

int xk_params_apply_profile(xk_params *params, const char *profile) {
  if (!params || !profile) return XK_ERROR;
  return x264_param_apply_profile(&params->param, profile);
}

int xk_params_validate(const xk_params *params) {
  if (!params) return XK_ERROR;
  const x264_param_t *p = &params->param;
  if (p->i_width <= 0 || p->i_height <= 0) return XK_ERROR;
  if (p->i_fps_num <= 0 || p->i_fps_den <= 0) return XK_ERROR;
  switch (p->i_csp) {
    case XK_CSP_I400:
      break;
    case XK_CSP_I420:
      /* x264 requires 4:2:0 chroma planes to be whole pixels. */
      if ((p->i_width & 1) || (p->i_height & 1)) return XK_ERROR;
      break;
    case XK_CSP_I422:
    case XK_CSP_I444:
      break;
    default:
      return XK_ERROR;
  }
  return 0;
}

int xk_params_get_int(const xk_params *params, int field) {
  if (!params) return XK_ERROR;
  const x264_param_t *p = &params->param;
  switch (field) {
    case XK_FIELD_WIDTH: return p->i_width;
    case XK_FIELD_HEIGHT: return p->i_height;
    case XK_FIELD_CHROMA_FORMAT: return p->i_csp;
    case XK_FIELD_BIT_DEPTH: return X264_BIT_DEPTH;
    case XK_FIELD_FPS_NUM: return p->i_fps_num;
    case XK_FIELD_FPS_DEN: return p->i_fps_den;
    case XK_FIELD_FRAME_THREADS: return p->i_threads;
    case XK_FIELD_LOG_LEVEL: return p->i_log_level;
    case XK_FIELD_REPEAT_HEADERS: return p->b_repeat_headers;
    case XK_FIELD_KEYFRAME_MAX: return p->i_keyint_max;
    default: return XK_ERROR;
  }
}

int xk_params_set_log_level(xk_params *params, int level) {
  if (!params) return XK_ERROR;
  params->param.i_log_level = level;
  return 0;
}

/* ------------------------------------------------------------------------- */
/* Encoder                                                                    */
/* ------------------------------------------------------------------------- */

static void xk_encoder_clear_output(xk_encoder *encoder) {
  encoder->out_nal_count = 0;
  encoder->out_slice_type = 0;
  encoder->out_pts = 0;
  encoder->out_dts = 0;
}

xk_encoder *xk_encoder_open(const xk_params *params) {
  if (!params) return NULL;

  xk_encoder *encoder = calloc(1, sizeof(*encoder));
  if (!encoder) return NULL;

  // x264_encoder_open() needs a mutable parameter block and keeps pointers to
  // its internally allocated zones. The binding never exposes zones, but clear
  // them so freeing both blocks can never free the same memory twice.
  encoder->params = *params;
  encoder->params.param.rc.zones = NULL;
  encoder->params.param.rc.i_zones = 0;

  encoder->enc = x264_encoder_open(&encoder->params.param);
  if (!encoder->enc) {
    free(encoder);
    return NULL;
  }
  x264_picture_init(&encoder->pic_out);
  xk_encoder_clear_output(encoder);
  return encoder;
}

void xk_encoder_close(xk_encoder *encoder) {
  if (!encoder) return;
  if (encoder->enc) x264_encoder_close(encoder->enc);
  x264_param_cleanup(&encoder->params.param);
  free(encoder->blob);
  free(encoder);
}

int xk_encoder_parameters(const xk_encoder *encoder, xk_params *out) {
  if (!encoder || !encoder->enc || !out) return XK_ERROR;
  x264_encoder_parameters(encoder->enc, &out->param);
  return 0;
}

/* Stores the NALs of the last encode call and the picture description. */
static int xk_encoder_capture(xk_encoder *encoder, int encoded) {
  encoder->out_nal_count = encoded > 0 ? encoder->nal_count : 0;
  encoder->out_slice_type = encoder->pic_out.i_type;
  encoder->out_pts = encoded > 0 ? encoder->pic_out.i_pts : 0;
  encoder->out_dts = encoded > 0 ? encoder->pic_out.i_dts : 0;
  return encoded > 0 ? XK_OK : XK_BUFFERED;
}

static int xk_encoder_encode_picture(xk_encoder *encoder, x264_picture_t *picture) {
  int encoded = x264_encoder_encode(encoder->enc, &encoder->nals, &encoder->nal_count,
                                    picture, &encoder->pic_out);
  if (encoded < 0) return XK_ERROR;
  return xk_encoder_capture(encoder, encoded);
}

static int xk_plane_height(int csp, int height) {
  switch (csp) {
    case XK_CSP_I400: return 0;
    case XK_CSP_I420: return (height + 1) / 2;
    default: return height;
  }
}

static int xk_plane_width(int csp, int width) {
  switch (csp) {
    case XK_CSP_I400: return 0;
    case XK_CSP_I420:
    case XK_CSP_I422: return (width + 1) / 2;
    default: return width;
  }
}

int xk_encode_planes(xk_encoder *encoder,
                     const uint8_t *y, const uint8_t *u, const uint8_t *v,
                     int stride_y, int stride_u, int stride_v,
                     int width, int height, int csp, int64_t pts) {
  if (!encoder || !encoder->enc || !y) return XK_ERROR;
  if (width <= 0 || height <= 0) return XK_ERROR;
  switch (csp) {
    case XK_CSP_I400:
    case XK_CSP_I422:
    case XK_CSP_I444:
      break;
    case XK_CSP_I420:
      if ((width & 1) || (height & 1)) return XK_ERROR;
      break;
    default:
      return XK_ERROR;
  }
  if (csp != XK_CSP_I400 && (!u || !v)) return XK_ERROR;
  /* Strides are bytes per row; a smaller value would make x264 read past the
   * caller's plane. */
  if (stride_y < width) return XK_ERROR;
  if (csp != XK_CSP_I400) {
    int chroma_width = xk_plane_width(csp, width);
    if (stride_u < chroma_width || stride_v < chroma_width) return XK_ERROR;
  }

  x264_picture_t picture;
  x264_picture_init(&picture);
  picture.img.i_csp = csp;
  picture.img.i_plane = csp == XK_CSP_I400 ? 1 : 3;
  picture.img.i_stride[0] = stride_y;
  picture.img.plane[0] = (uint8_t *)y;
  if (csp != XK_CSP_I400) {
    picture.img.i_stride[1] = stride_u;
    picture.img.i_stride[2] = stride_v;
    picture.img.plane[1] = (uint8_t *)u;
    picture.img.plane[2] = (uint8_t *)v;
  }
  picture.i_pts = pts;
  picture.i_type = X264_TYPE_AUTO;

  return xk_encoder_encode_picture(encoder, &picture);
}

/* Packed picture packet, produced by the web bridge (X264Packing in
 * X264Web.kt). All integers little-endian. */
#define XK_INPUT_MAGIC 0x50594E49
#define XK_INPUT_HEADER 56

static int32_t xk_read_i32(const uint8_t *data) {
  return (int32_t)((uint32_t)data[0] | ((uint32_t)data[1] << 8) |
                   ((uint32_t)data[2] << 16) | ((uint32_t)data[3] << 24));
}

static int64_t xk_read_i64(const uint8_t *data) {
  uint64_t low = (uint32_t)xk_read_i32(data);
  uint64_t high = (uint32_t)xk_read_i32(data + 4);
  return (int64_t)(low | (high << 32));
}


int xk_encode_packed(xk_encoder *encoder, const uint8_t *data, int size) {
  if (!encoder || !encoder->enc || !data || size < XK_INPUT_HEADER) return XK_ERROR;
  if ((uint32_t)xk_read_i32(data) != (uint32_t)XK_INPUT_MAGIC) return XK_ERROR;

  int width = xk_read_i32(data + 4);
  int height = xk_read_i32(data + 8);
  int csp = xk_read_i32(data + 12);
  int strides[3] = {xk_read_i32(data + 16), xk_read_i32(data + 20), xk_read_i32(data + 24)};
  int64_t pts = xk_read_i64(data + 32);
  int offsets[3] = {xk_read_i32(data + 40), xk_read_i32(data + 44), xk_read_i32(data + 48)};

  if (width <= 0 || height <= 0) return XK_ERROR;
  if (csp != XK_CSP_I400 && csp != XK_CSP_I420 && csp != XK_CSP_I422 && csp != XK_CSP_I444) {
    return XK_ERROR;
  }

  int plane_widths[3] = {width, xk_plane_width(csp, width), xk_plane_width(csp, width)};
  int plane_heights[3] = {height, xk_plane_height(csp, height), xk_plane_height(csp, height)};
  int plane_count = csp == XK_CSP_I400 ? 1 : 3;

  const uint8_t *planes[3] = {NULL, NULL, NULL};
  for (int index = 0; index < plane_count; index++) {
    if (strides[index] < plane_widths[index]) return XK_ERROR;
    int offset = offsets[index];
    if (offset < XK_INPUT_HEADER) return XK_ERROR;
    int64_t end = (int64_t)offset + (int64_t)strides[index] * (int64_t)plane_heights[index];
    if (end > size) return XK_ERROR;
    planes[index] = data + offset;
  }

  return xk_encode_planes(encoder, planes[0], planes[1], planes[2],
                          strides[0], strides[1], strides[2],
                          width, height, csp, pts);
}

int xk_encoder_headers(xk_encoder *encoder) {
  if (!encoder || !encoder->enc) return XK_ERROR;
  int result = x264_encoder_headers(encoder->enc, &encoder->nals, &encoder->nal_count);
  if (result < 0) return XK_ERROR;
  encoder->out_nal_count = encoder->nal_count;
  encoder->out_slice_type = X264_TYPE_AUTO;
  encoder->out_pts = 0;
  encoder->out_dts = 0;
  return encoder->out_nal_count > 0 ? XK_OK : XK_BUFFERED;
}

int xk_flush(xk_encoder *encoder) {
  if (!encoder || !encoder->enc) return XK_ERROR;
  /* With frame threads, a single encode(NULL) call may rotate to a context
   * that has nothing to emit yet. Keep going while x264 reports buffered
   * frames; the Kotlin loop stops on the first XK_BUFFERED. */
  while (x264_encoder_delayed_frames(encoder->enc) > 0) {
    int encoded = x264_encoder_encode(encoder->enc, &encoder->nals, &encoder->nal_count,
                                      NULL, &encoder->pic_out);
    if (encoded < 0) return XK_ERROR;
    if (encoded > 0) return xk_encoder_capture(encoder, encoded);
  }
  return XK_BUFFERED;
}

/* ------------------------------------------------------------------------- */
/* Output                                                                     */
/* ------------------------------------------------------------------------- */

/* Returns the NAL payload without the transport framing x264 configured:
 * a start code for Annex-B output, a four-byte length for the other mode. */
static const uint8_t *xk_nal_payload(const x264_nal_t *nal, int *size) {
  const uint8_t *payload = nal->p_payload;
  int length = nal->i_payload;
  if (length >= 4 && payload[0] == 0 && payload[1] == 0 && payload[2] == 0 && payload[3] == 1) {
    payload += 4;
    length -= 4;
  } else if (length >= 3 && payload[0] == 0 && payload[1] == 0 && payload[2] == 1) {
    payload += 3;
    length -= 3;
  } else if (length >= 4) {
    /* b_annexb=0: x264 prefixes the NAL with its four-byte size. */
    int prefix = (payload[0] << 24) | (payload[1] << 16) | (payload[2] << 8) | payload[3];
    if (prefix > 0 && prefix == length - 4) {
      payload += 4;
      length -= 4;
    }
  }
  if (length < 0) length = 0;
  *size = length;
  return payload;
}

int xk_output_nal_count(const xk_encoder *encoder) {
  return encoder ? encoder->out_nal_count : 0;
}

int xk_output_nal_type(const xk_encoder *encoder, int index) {
  if (!encoder || index < 0 || index >= encoder->out_nal_count) return -1;
  /* i_type holds the bare nal_unit_type; mask off the ref_idc bits defensively
   * because older x264 revisions packed them together. */
  return encoder->nals[index].i_type & 0x1f;
}

const uint8_t *xk_output_nal_data(const xk_encoder *encoder, int index, int *size) {
  if (size) *size = 0;
  if (!encoder || index < 0 || index >= encoder->out_nal_count) return NULL;
  int length = 0;
  const uint8_t *payload = xk_nal_payload(&encoder->nals[index], &length);
  if (size) *size = length;
  return payload;
}

int xk_output_slice_type(const xk_encoder *encoder) {
  return encoder ? encoder->out_slice_type : 0;
}

int64_t xk_output_pts(const xk_encoder *encoder) {
  return encoder ? encoder->out_pts : 0;
}

int64_t xk_output_dts(const xk_encoder *encoder) {
  return encoder ? encoder->out_dts : 0;
}

static int xk_blob_ensure(xk_encoder *encoder, int capacity) {
  if (encoder->blob_capacity >= capacity) return 0;
  uint8_t *grown = realloc(encoder->blob, (size_t)capacity);
  if (!grown) return XK_ERROR;
  encoder->blob = grown;
  encoder->blob_capacity = capacity;
  return 0;
}

static int xk_align8(int value) { return (value + 7) & ~7; }

static void xk_write_i32(uint8_t *destination, int value) {
  destination[0] = (uint8_t)(value);
  destination[1] = (uint8_t)(value >> 8);
  destination[2] = (uint8_t)(value >> 16);
  destination[3] = (uint8_t)(value >> 24);
}

static void xk_write_i64(uint8_t *destination, int64_t value) {
  uint64_t bits = (uint64_t)value;
  xk_write_i32(destination, (int)(uint32_t)bits);
  xk_write_i32(destination + 4, (int)(uint32_t)(bits >> 32));
}

const uint8_t *xk_output_blob(xk_encoder *encoder, int *size) {
  if (size) *size = 0;
  if (!encoder) return NULL;

  int count = encoder->out_nal_count;
  int header = 40;
  int metadata = count * 12;
  int payload_size = 0;
  for (int index = 0; index < count; index++) {
    int length = 0;
    xk_nal_payload(&encoder->nals[index], &length);
    payload_size += length;
  }

  int payload_base = xk_align8(header + metadata);
  int total = payload_base + payload_size;
  /* Never hand out a zero-sized blob: the Kotlin parser requires the magic. */
  if (total < payload_base) return NULL;
  if (xk_blob_ensure(encoder, total == 0 ? 1 : total) < 0) return NULL;

  uint8_t *blob = encoder->blob;
  memset(blob, 0, (size_t)payload_base);
  xk_write_i32(blob, XK_OUTPUT_MAGIC);
  xk_write_i32(blob + 4, XK_OUTPUT_VERSION);
  xk_write_i32(blob + 8, count);
  xk_write_i32(blob + 12, encoder->out_slice_type);
  xk_write_i64(blob + 16, encoder->out_pts);
  xk_write_i64(blob + 24, encoder->out_dts);
  xk_write_i32(blob + 32, payload_size);
  xk_write_i32(blob + 36, 0);

  int offset = 0;
  for (int index = 0; index < count; index++) {
    int length = 0;
    const uint8_t *payload = xk_nal_payload(&encoder->nals[index], &length);
    xk_write_i32(blob + header + index * 4, encoder->nals[index].i_type & 0x1f);
    xk_write_i32(blob + header + count * 4 + index * 4, offset);
    xk_write_i32(blob + header + count * 8 + index * 4, length);
    if (length > 0 && payload) memcpy(blob + payload_base + offset, payload, (size_t)length);
    offset += length;
  }

  if (size) *size = total;
  return blob;
}
