// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Thin JNI layer over dtbridge.h (brief G0). Only the bridge's API is used
 * here; darktable's own headers are never included above the bridge.
 *
 * Also two helpers that are plain POSIX, not darktable:
 * - capture of stdout and stderr (darktable logs with printf) into a ring of
 *   lines that the app reads, and into logcat;
 * - a dlopen() check for every plugin file, which is experiment S4: can the
 *   app load native code that it unpacked into its own storage.
 */
#include "dtbridge.h"

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "darktable"

/* ------------------------------------------------------------------ */
/* log capture                                                        */

#define LOG_LINES 4000
#define LOG_LINE_MAX 1024

static pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;
static char *g_log_ring[LOG_LINES];
static long long g_log_written = 0;  // lines ever written
static int g_log_started = 0;

static void _log_push(const char *line)
{
  __android_log_write(ANDROID_LOG_INFO, TAG, line);
  pthread_mutex_lock(&g_log_mutex);
  const int i = (int)(g_log_written % LOG_LINES);
  free(g_log_ring[i]);
  g_log_ring[i] = strdup(line);
  g_log_written++;
  pthread_mutex_unlock(&g_log_mutex);
}

static void *_log_reader(void *arg)
{
  const int fd = (int)(intptr_t)arg;
  char buf[LOG_LINE_MAX];
  size_t len = 0;
  for(;;)
  {
    const ssize_t n = read(fd, buf + len, sizeof(buf) - 1 - len);
    if(n < 0 && errno == EINTR) continue;
    if(n <= 0) break;
    len += (size_t)n;
    size_t start = 0;
    for(size_t i = 0; i < len; i++)
      if(buf[i] == '\n')
      {
        buf[i] = '\0';
        _log_push(buf + start);
        start = i + 1;
      }
    if(start > 0)
    {
      memmove(buf, buf + start, len - start);
      len -= start;
    }
    else if(len == sizeof(buf) - 1)  // a line longer than the buffer
    {
      buf[len] = '\0';
      _log_push(buf);
      len = 0;
    }
  }
  return NULL;
}

/* Redirect stdout and stderr of the whole process into the ring. */
JNIEXPORT jboolean JNICALL
Java_app_dtandroid_engine_NativeBridge_startLogCapture(JNIEnv *env, jclass cls)
{
  if(g_log_started) return JNI_TRUE;
  int fds[2];
  if(pipe(fds)) return JNI_FALSE;
  setvbuf(stdout, NULL, _IOLBF, 0);
  setvbuf(stderr, NULL, _IONBF, 0);
  if(dup2(fds[1], STDOUT_FILENO) < 0 || dup2(fds[1], STDERR_FILENO) < 0) return JNI_FALSE;
  close(fds[1]);
  pthread_t t;
  if(pthread_create(&t, NULL, _log_reader, (void *)(intptr_t)fds[0])) return JNI_FALSE;
  pthread_detach(t);
  g_log_started = 1;
  return JNI_TRUE;
}

/* Lines written after line number `since` (0 = from the oldest kept), joined
 * with '\n'. *next (array of one long) receives the number to pass next. */
JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_logSince(JNIEnv *env, jclass cls, jlong since, jlongArray next)
{
  pthread_mutex_lock(&g_log_mutex);
  long long from = since;
  if(from < g_log_written - LOG_LINES) from = g_log_written - LOG_LINES;
  if(from < 0) from = 0;
  size_t total = 1;
  for(long long k = from; k < g_log_written; k++) total += strlen(g_log_ring[k % LOG_LINES]) + 1;
  char *out = malloc(total);
  size_t pos = 0;
  if(out)
  {
    for(long long k = from; k < g_log_written; k++)
    {
      const char *l = g_log_ring[k % LOG_LINES];
      const size_t n = strlen(l);
      memcpy(out + pos, l, n);
      pos += n;
      out[pos++] = '\n';
    }
    out[pos] = '\0';
  }
  const jlong written = g_log_written;
  pthread_mutex_unlock(&g_log_mutex);
  (*env)->SetLongArrayRegion(env, next, 0, 1, &written);
  if(!out) return NULL;
  // darktable may print bytes that are not valid modified UTF-8: replace them
  for(size_t i = 0; i < pos; i++)
    if((unsigned char)out[i] >= 0x80 || out[i] == '\0') out[i] = '?';
  jstring s = (*env)->NewStringUTF(env, out);
  free(out);
  return s;
}

JNIEXPORT void JNICALL
Java_app_dtandroid_engine_NativeBridge_logLine(JNIEnv *env, jclass cls, jstring line)
{
  const char *l = (*env)->GetStringUTFChars(env, line, NULL);
  if(!l) return;
  _log_push(l);
  (*env)->ReleaseStringUTFChars(env, line, l);
}

/* ------------------------------------------------------------------ */
/* S4: can a library unpacked into app storage be loaded?             */

/* dlopen + dlclose one file. Returns NULL on success, else dlerror(). */
JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_dlopenCheck(JNIEnv *env, jclass cls, jstring path)
{
  const char *p = (*env)->GetStringUTFChars(env, path, NULL);
  if(!p) return (*env)->NewStringUTF(env, "no path");
  dlerror();
  void *h = dlopen(p, RTLD_NOW | RTLD_LOCAL);
  (*env)->ReleaseStringUTFChars(env, path, p);
  if(!h)
  {
    const char *e = dlerror();
    return (*env)->NewStringUTF(env, e ? e : "dlopen failed");
  }
  dlclose(h);
  return NULL;
}

/* ------------------------------------------------------------------ */
/* helpers                                                            */

typedef struct jstr_t
{
  JNIEnv *env;
  jstring js;
  const char *s;
} jstr_t;

static jstr_t _str(JNIEnv *env, jstring js)
{
  jstr_t r = { env, js, js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL };
  return r;
}

static void _str_free(jstr_t *r)
{
  if(r->s) (*r->env)->ReleaseStringUTFChars(r->env, r->js, r->s);
  r->s = NULL;
}

/* a bridge JSON string -> Java string; frees it */
static jstring _json(JNIEnv *env, char *s)
{
  if(!s) return NULL;
  jstring r = (*env)->NewStringUTF(env, s);
  dtb_free(s);
  return r;
}

/* ------------------------------------------------------------------ */
/* lifecycle                                                          */

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_init(JNIEnv *env, jclass cls, jstring configdir,
                                            jstring cachedir, jstring datadir, jstring moduledir,
                                            jstring tmpdir, jstring library, jint threads)
{
  jstr_t c = _str(env, configdir), ca = _str(env, cachedir), d = _str(env, datadir),
         m = _str(env, moduledir), t = _str(env, tmpdir), l = _str(env, library);
  const dtb_paths_t p = { .configdir = c.s, .cachedir = ca.s, .datadir = d.s, .moduledir = m.s,
                          .tmpdir = t.s, .library = l.s, .threads = threads };
  const int r = dtb_init(&p);
  _str_free(&c); _str_free(&ca); _str_free(&d); _str_free(&m); _str_free(&t); _str_free(&l);
  return r;
}

JNIEXPORT void JNICALL
Java_app_dtandroid_engine_NativeBridge_shutdown(JNIEnv *env, jclass cls)
{
  dtb_shutdown();
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_lastError(JNIEnv *env, jclass cls)
{
  return (*env)->NewStringUTF(env, dtb_last_error());
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_engineVersion(JNIEnv *env, jclass cls)
{
  return (*env)->NewStringUTF(env, dtb_engine_version());
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_pluginsJson(JNIEnv *env, jclass cls)
{
  return _json(env, dtb_plugins_json());
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_setThreads(JNIEnv *env, jclass cls, jint n)
{
  return dtb_set_threads(n);
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_getThreads(JNIEnv *env, jclass cls)
{
  return dtb_get_threads();
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_cacheDrop(JNIEnv *env, jclass cls)
{
  return dtb_cache_drop();
}

/* ------------------------------------------------------------------ */
/* session                                                            */

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_xmpCheck(JNIEnv *env, jclass cls, jstring path)
{
  jstr_t p = _str(env, path);
  int n = 0;
  char *r = dtb_xmp_check(p.s, &n);
  _str_free(&p);
  return _json(env, r);
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_open(JNIEnv *env, jclass cls, jstring raw, jstring xmp)
{
  jstr_t r = _str(env, raw), x = _str(env, xmp);
  const int res = dtb_open(r.s, x.s);
  _str_free(&r); _str_free(&x);
  return res;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_close(JNIEnv *env, jclass cls)
{
  return dtb_close();
}

JNIEXPORT jboolean JNICALL
Java_app_dtandroid_engine_NativeBridge_isReadOnly(JNIEnv *env, jclass cls)
{
  return dtb_is_read_only() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_modulesJson(JNIEnv *env, jclass cls)
{
  return _json(env, dtb_modules_json());
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_historyJson(JNIEnv *env, jclass cls)
{
  return _json(env, dtb_history_json());
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_paramSetFloat(JNIEnv *env, jclass cls, jstring op, jint prio,
                                                     jstring param, jfloat value)
{
  jstr_t o = _str(env, op), p = _str(env, param);
  const int r = dtb_param_set_float(o.s, prio, p.s, value);
  _str_free(&o); _str_free(&p);
  return r;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_setEnabled(JNIEnv *env, jclass cls, jstring op, jint prio,
                                                  jboolean enabled)
{
  jstr_t o = _str(env, op);
  const int r = dtb_set_enabled(o.s, prio, enabled ? 1 : 0);
  _str_free(&o);
  return r;
}

JNIEXPORT void JNICALL
Java_app_dtandroid_engine_NativeBridge_historySeal(JNIEnv *env, jclass cls)
{
  dtb_history_seal();
}

/* ------------------------------------------------------------------ */
/* rendering                                                          */

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_renderSize(JNIEnv *env, jclass cls, jfloat scale, jintArray wh)
{
  int w = 0, h = 0;
  const int r = dtb_render_size(scale, &w, &h);
  const jint v[2] = { w, h };
  (*env)->SetIntArrayRegion(env, wh, 0, 2, v);
  return r;
}

/* into a direct ByteBuffer of at least w*h*4 bytes */
JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_render(JNIEnv *env, jclass cls, jint x, jint y, jint w, jint h,
                                              jfloat scale, jobject buffer)
{
  uint8_t *out = (*env)->GetDirectBufferAddress(env, buffer);
  const jlong cap = (*env)->GetDirectBufferCapacity(env, buffer);
  if(!out || w <= 0 || h <= 0 || cap < (jlong)w * h * 4) return DTB_E_ARG;
  return dtb_render(x, y, w, h, scale, out);
}

/* returns RGBA bytes, wh receives width and height; NULL on failure (the
 * code is in status[0]) */
JNIEXPORT jbyteArray JNICALL
Java_app_dtandroid_engine_NativeBridge_renderPreview(JNIEnv *env, jclass cls, jint max_size,
                                                     jintArray wh, jintArray status)
{
  uint8_t *buf = NULL;
  int w = 0, h = 0;
  const int r = dtb_render_preview(max_size, &buf, &w, &h);
  const jint st = r;
  (*env)->SetIntArrayRegion(env, status, 0, 1, &st);
  if(r || !buf)
  {
    dtb_free(buf);
    return NULL;
  }
  const jint v[2] = { w, h };
  (*env)->SetIntArrayRegion(env, wh, 0, 2, v);
  const jsize n = (jsize)((size_t)w * h * 4);
  jbyteArray a = (*env)->NewByteArray(env, n);
  if(a) (*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte *)buf);
  dtb_free(buf);
  return a;
}

JNIEXPORT void JNICALL
Java_app_dtandroid_engine_NativeBridge_renderCancel(JNIEnv *env, jclass cls)
{
  dtb_render_cancel();
}

/* ------------------------------------------------------------------ */
/* output                                                             */

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_xmpWrite(JNIEnv *env, jclass cls, jstring path)
{
  jstr_t p = _str(env, path);
  const int r = dtb_xmp_write(p.s);
  _str_free(&p);
  return r;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_exportJpeg(JNIEnv *env, jclass cls, jstring path, jint quality,
                                                  jint max_w, jint max_h)
{
  jstr_t p = _str(env, path);
  const int r = dtb_export_jpeg(p.s, quality, max_w, max_h);
  _str_free(&p);
  return r;
}
