/*
 * JNI bridge between cn.enaium.x264.JniX264 and the C shim.
 *
 * The declarations on the Kotlin side live in
 * x264-kmp/src/jvmMain/kotlin/cn/enaium/x264/X264Native.jvm.kt.
 *
 * Handles are the raw xk_params / xk_encoder pointers stored in a jlong. All
 * byte arrays are copied into/out of the shim; the shim is responsible for
 * keeping picture data alive only for the duration of a call, which matches
 * x264_encoder_encode()'s contract (it copies the input frame itself).
 */
#include <jni.h>
#include <stdint.h>

#include "x264_kmp.h"

static xk_params *xk_params_from(jlong handle) {
  return (xk_params *)(intptr_t)handle;
}

static xk_encoder *xk_encoder_from(jlong handle) {
  return (xk_encoder *)(intptr_t)handle;
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeVersionPacked(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return xk_version_packed();
}

JNIEXPORT jstring JNICALL
Java_cn_enaium_x264_JniX264_nativeVersionString(JNIEnv *env, jobject self) {
  (void)self;
  const char *version = xk_version_string();
  return (*env)->NewStringUTF(env, version ? version : "");
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeApiBuild(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return xk_api_build();
}

JNIEXPORT jlong JNICALL
Java_cn_enaium_x264_JniX264_nativeParamsCreate(JNIEnv *env, jobject self,
                                               jint width, jint height, jint csp) {
  (void)env;
  (void)self;
  return (jlong)(intptr_t)xk_params_create(width, height, csp);
}

JNIEXPORT void JNICALL
Java_cn_enaium_x264_JniX264_nativeParamsFree(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  xk_params_free(xk_params_from(handle));
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeParamsParse(JNIEnv *env, jobject self, jlong handle,
                                              jstring name, jstring value) {
  (void)self;
  if (!name) return XK_ERROR;
  const char *name_chars = (*env)->GetStringUTFChars(env, name, NULL);
  if (!name_chars) return XK_ERROR;
  const char *value_chars = NULL;
  if (value) {
    value_chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (!value_chars) {
      (*env)->ReleaseStringUTFChars(env, name, name_chars);
      return XK_ERROR;
    }
  }
  int result = xk_params_parse(xk_params_from(handle), name_chars, value_chars);
  if (value_chars) (*env)->ReleaseStringUTFChars(env, value, value_chars);
  (*env)->ReleaseStringUTFChars(env, name, name_chars);
  return result;
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeParamsPreset(JNIEnv *env, jobject self, jlong handle,
                                               jstring preset) {
  (void)self;
  if (!preset) return XK_ERROR;
  const char *chars = (*env)->GetStringUTFChars(env, preset, NULL);
  if (!chars) return XK_ERROR;
  int result = xk_params_apply_preset(xk_params_from(handle), chars);
  (*env)->ReleaseStringUTFChars(env, preset, chars);
  return result;
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeParamsTune(JNIEnv *env, jobject self, jlong handle,
                                             jstring tune) {
  (void)self;
  if (!tune) return XK_ERROR;
  const char *chars = (*env)->GetStringUTFChars(env, tune, NULL);
  if (!chars) return XK_ERROR;
  int result = xk_params_apply_tune(xk_params_from(handle), chars);
  (*env)->ReleaseStringUTFChars(env, tune, chars);
  return result;
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeParamsProfile(JNIEnv *env, jobject self, jlong handle,
                                                jstring profile) {
  (void)self;
  if (!profile) return XK_ERROR;
  const char *chars = (*env)->GetStringUTFChars(env, profile, NULL);
  if (!chars) return XK_ERROR;
  int result = xk_params_apply_profile(xk_params_from(handle), chars);
  (*env)->ReleaseStringUTFChars(env, profile, chars);
  return result;
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeParamsValidate(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  return xk_params_validate(xk_params_from(handle));
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeParamsGetInt(JNIEnv *env, jobject self, jlong handle,
                                               jint field) {
  (void)env;
  (void)self;
  return xk_params_get_int(xk_params_from(handle), field);
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeParamsSetLogLevel(JNIEnv *env, jobject self, jlong handle,
                                                    jint level) {
  (void)env;
  (void)self;
  return xk_params_set_log_level(xk_params_from(handle), level);
}

JNIEXPORT jlong JNICALL
Java_cn_enaium_x264_JniX264_nativeEncoderOpen(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  return (jlong)(intptr_t)xk_encoder_open(xk_params_from(handle));
}

JNIEXPORT void JNICALL
Java_cn_enaium_x264_JniX264_nativeEncoderClose(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  xk_encoder_close(xk_encoder_from(handle));
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeEncoderParameters(JNIEnv *env, jobject self,
                                                    jlong encoder, jlong params) {
  (void)env;
  (void)self;
  return xk_encoder_parameters(xk_encoder_from(encoder), xk_params_from(params));
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeEncoderHeaders(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  return xk_encoder_headers(xk_encoder_from(handle));
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeEncode(JNIEnv *env, jobject self, jlong handle,
                                         jbyteArray y, jbyteArray u, jbyteArray v,
                                         jint width, jint height, jint csp,
                                         jint stride_y, jint stride_u, jint stride_v,
                                         jlong pts) {
  (void)self;
  xk_encoder *encoder = xk_encoder_from(handle);
  if (!encoder || !y) return XK_ERROR;

  jbyte *y_chars = (*env)->GetByteArrayElements(env, y, NULL);
  jbyte *u_chars = NULL;
  jbyte *v_chars = NULL;
  if (!y_chars) return XK_ERROR;
  if (u) {
    u_chars = (*env)->GetByteArrayElements(env, u, NULL);
    if (!u_chars) {
      (*env)->ReleaseByteArrayElements(env, y, y_chars, JNI_ABORT);
      return XK_ERROR;
    }
  }
  if (v) {
    v_chars = (*env)->GetByteArrayElements(env, v, NULL);
    if (!v_chars) {
      if (u_chars) (*env)->ReleaseByteArrayElements(env, u, u_chars, JNI_ABORT);
      (*env)->ReleaseByteArrayElements(env, y, y_chars, JNI_ABORT);
      return XK_ERROR;
    }
  }

  int result = xk_encode_planes(
      encoder,
      (const uint8_t *)y_chars,
      (const uint8_t *)u_chars,
      (const uint8_t *)v_chars,
      stride_y, stride_u, stride_v,
      width, height, csp, (int64_t)pts);

  if (v_chars) (*env)->ReleaseByteArrayElements(env, v, v_chars, JNI_ABORT);
  if (u_chars) (*env)->ReleaseByteArrayElements(env, u, u_chars, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, y, y_chars, JNI_ABORT);
  return result;
}

JNIEXPORT jint JNICALL
Java_cn_enaium_x264_JniX264_nativeFlush(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  return xk_flush(xk_encoder_from(handle));
}

JNIEXPORT jbyteArray JNICALL
Java_cn_enaium_x264_JniX264_nativeOutputBlob(JNIEnv *env, jobject self, jlong handle) {
  (void)self;
  int size = 0;
  const uint8_t *blob = xk_output_blob(xk_encoder_from(handle), &size);
  if (!blob || size <= 0) return NULL;
  jbyteArray result = (*env)->NewByteArray(env, size);
  if (!result) return NULL;
  (*env)->SetByteArrayRegion(env, result, 0, size, (const jbyte *)blob);
  return result;
}
