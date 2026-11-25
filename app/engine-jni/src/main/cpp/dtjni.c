// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Thin JNI layer over dtbridge.h (brief G0; adapted to Engine API v1 in P1a,
 * keeping the Kotlin side of the G0 app unchanged where possible). Only the bridge's API is used
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
#include <time.h>
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

/* the result of a bridge call that returns JSON through char **: the JSON,
 * or NULL on failure (the message is in dtb_last_error) */
typedef int (*_json_call_t)(char **);
static jstring _json_call(JNIEnv *env, _json_call_t f)
{
  char *s = NULL;
  if(f(&s) != DTB_OK) return NULL;
  return _json(env, s);
}

/* the value after "key": in the bridge's compact JSON (no parser here: the G0
 * layer only needs a few top-level fields of engine_info and image_info) */
static const char *_json_field(const char *json, const char *key)
{
  char pat[64];
  snprintf(pat, sizeof(pat), "\"%s\":", key);
  const char *p = json ? strstr(json, pat) : NULL;
  return p ? p + strlen(pat) : NULL;
}

static int _info_int(const char *key)
{
  char *s = NULL;
  if(dtb_engine_info(&s) != DTB_OK) return -1;
  const char *v = _json_field(s, key);
  const int r = v ? atoi(v) : -1;
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
                          .tmpdir = t.s, .library = l.s };
  const int r = dtb_init(&p, threads);
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
  char *s = NULL;
  if(dtb_engine_info(&s) != DTB_OK) return (*env)->NewStringUTF(env, "?");
  char version[128] = "?";
  const char *v = _json_field(s, "darktable_version");
  if(v && *v == '"')
  {
    size_t k = 0;
    for(v++; *v && *v != '"' && k + 1 < sizeof(version); v++) version[k++] = *v;
    version[k] = '\0';
  }
  dtb_free(s);
  return (*env)->NewStringUTF(env, version);
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_pluginsJson(JNIEnv *env, jclass cls)
{
  // engine_info; the plugin report is its "plugins" member
  return _json_call(env, dtb_engine_info);
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_setThreads(JNIEnv *env, jclass cls, jint n)
{
  // returns the count now in effect, as the G0 benchmark expects
  const int r = dtb_set_threads(n);
  return r != DTB_OK ? r : _info_int("threads");
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_getThreads(JNIEnv *env, jclass cls)
{
  return _info_int("threads");
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
  char *r = NULL;
  dtb_xmp_check(p.s, &r);
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
  char *s = NULL;
  if(dtb_image_info(&s) != DTB_OK) return JNI_FALSE;
  const char *v = _json_field(s, "read_only");
  const jboolean r = v && !strncmp(v, "true", 4) ? JNI_TRUE : JNI_FALSE;
  dtb_free(s);
  return r;
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_modulesJson(JNIEnv *env, jclass cls)
{
  return _json_call(env, dtb_modules);
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_historyJson(JNIEnv *env, jclass cls)
{
  return _json_call(env, dtb_history);
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_paramSetFloat(JNIEnv *env, jclass cls, jstring op, jint prio,
                                                     jstring param, jfloat value)
{
  jstr_t o = _str(env, op), p = _str(env, param);
  char v[32];
  snprintf(v, sizeof(v), "%.9g", (double)value);
  const int r = dtb_param_set(o.s, prio, p.s, v);
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
  (void)dtb_history_seal();
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
  return dtb_render_region(x, y, w, h, scale, out);
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
  char opts[160];
  snprintf(opts, sizeof(opts), "{\"format\":\"jpeg\",\"quality\":%d,\"max_width\":%d,\"max_height\":%d}",
           quality, max_w, max_h);
  const int r = dtb_export(p.s, opts);
  _str_free(&p);
  return r;
}

/* ================================================================== */
/* Engine API v1, the whole core (brief P2b): one native per bridge   */
/* call, used by JniEngine (DarktableEngine over JNI). Functions that */
/* return JSON put the bridge's code into status[0] and return the    */
/* JSON text, or NULL on failure. Nothing here parses JSON.           */
/* ================================================================== */

static void _status(JNIEnv *env, jintArray status, int code)
{
  if(!status) return;
  const jint c = code;
  (*env)->SetIntArrayRegion(env, status, 0, 1, &c);
}

/* JSON text -> Java string. The bridge's JSON is UTF-8; NewStringUTF wants
 * modified UTF-8, which differs only for NUL and characters outside the BMP.
 * JSON from json-glib never contains a raw NUL; supplementary characters
 * (possible in Exif text) are replaced, as the G0 log reader does. */
static jstring _json_status(JNIEnv *env, int code, char *s, jintArray status)
{
  _status(env, status, code);
  if(code != DTB_OK || !s)
  {
    dtb_free(s);
    return NULL;
  }
  for(unsigned char *p = (unsigned char *)s; *p; p++)
    if(*p >= 0xf0)
    {
      // a 4-byte UTF-8 sequence: replace every byte of it with '?'
      int n = 1;
      while(n < 4 && p[n] && (p[n] & 0xc0) == 0x80) n++;
      for(int k = 0; k < n; k++) p[k] = '?';
      p += n - 1;
    }
  jstring r = (*env)->NewStringUTF(env, s);
  dtb_free(s);
  return r;
}

/* the JSON getters without arguments */
#define DTJ_ENGINE_INFO 0
#define DTJ_IMAGE_INFO 1
#define DTJ_MODULES 2
#define DTJ_HISTORY 3
#define DTJ_UNDO_STATE 4
#define DTJ_MEMORY_INFO 5

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_jsonGet(JNIEnv *env, jclass cls, jint what, jintArray status)
{
  char *s = NULL;
  int r;
  switch(what)
  {
    case DTJ_ENGINE_INFO: r = dtb_engine_info(&s); break;
    case DTJ_IMAGE_INFO: r = dtb_image_info(&s); break;
    case DTJ_MODULES: r = dtb_modules(&s); break;
    case DTJ_HISTORY: r = dtb_history(&s); break;
    case DTJ_UNDO_STATE: r = dtb_undo_state(&s); break;
    case DTJ_MEMORY_INFO: r = dtb_memory_info(&s); break;
    default: r = DTB_E_ARG; break;
  }
  return _json_status(env, r, s, status);
}

/* brief P4a: give memory back (engine-api.md 10); the memory_info afterwards */
JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_memoryTrim(JNIEnv *env, jclass cls, jint level, jintArray status)
{
  char *s = NULL;
  const int r = dtb_memory_trim(level, &s);
  return _json_status(env, r, s, status);
}

/* modules() split into its two costs, for the timings page: the bridge call
 * alone (times[0], ns) and the copy into a Java string (times[1], ns). */
JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_modulesTimed(JNIEnv *env, jclass cls, jlongArray times, jintArray status)
{
  struct timespec a, b, c;
  char *s = NULL;
  clock_gettime(CLOCK_MONOTONIC, &a);
  const int r = dtb_modules(&s);
  clock_gettime(CLOCK_MONOTONIC, &b);
  jstring j = _json_status(env, r, s, status);
  clock_gettime(CLOCK_MONOTONIC, &c);
  const jlong t[2] = { (b.tv_sec - a.tv_sec) * 1000000000LL + (b.tv_nsec - a.tv_nsec),
                       (c.tv_sec - b.tv_sec) * 1000000000LL + (c.tv_nsec - b.tv_nsec) };
  (*env)->SetLongArrayRegion(env, times, 0, 2, t);
  return j;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_setThreadsCode(JNIEnv *env, jclass cls, jint n)
{
  return dtb_set_threads(n);
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_setThreadCpus(JNIEnv *env, jclass cls, jintArray cpus)
{
  const jsize n = cpus ? (*env)->GetArrayLength(env, cpus) : 0;
  if(n == 0) return dtb_set_thread_cpus(NULL, 0);
  jint *ids = (*env)->GetIntArrayElements(env, cpus, NULL);
  if(!ids) return DTB_E_ARG;
  int *c = malloc(sizeof(int) * (size_t)n);
  for(jsize i = 0; i < n; i++) c[i] = ids[i];
  (*env)->ReleaseIntArrayElements(env, cpus, ids, JNI_ABORT);
  const int r = dtb_set_thread_cpus(c, n);
  free(c);
  return r;
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_xmpCheckStatus(JNIEnv *env, jclass cls, jstring path, jintArray status)
{
  jstr_t p = _str(env, path);
  char *s = NULL;
  const int r = dtb_xmp_check(p.s, &s);
  _str_free(&p);
  return _json_status(env, r, s, status);
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_metadataReload(JNIEnv *env, jclass cls, jstring path)
{
  jstr_t p = _str(env, path);
  const int r = dtb_metadata_reload(p.s);
  _str_free(&p);
  return r;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_paramSet(JNIEnv *env, jclass cls, jstring op, jint prio,
                                                jstring path, jstring value_json)
{
  jstr_t o = _str(env, op), p = _str(env, path), v = _str(env, value_json);
  const int r = dtb_param_set(o.s, prio, p.s, v.s);
  _str_free(&o); _str_free(&p); _str_free(&v);
  return r;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_paramsSet(JNIEnv *env, jclass cls, jstring op, jint prio,
                                                 jstring values_json)
{
  jstr_t o = _str(env, op), v = _str(env, values_json);
  const int r = dtb_params_set(o.s, prio, v.s);
  _str_free(&o); _str_free(&v);
  return r;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_moduleReset(JNIEnv *env, jclass cls, jstring op, jint prio)
{
  jstr_t o = _str(env, op);
  const int r = dtb_module_reset(o.s, prio);
  _str_free(&o);
  return r;
}

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_moduleAction(JNIEnv *env, jclass cls, jstring op, jint prio,
                                                    jstring action, jstring args_json, jintArray status)
{
  jstr_t o = _str(env, op), a = _str(env, action), g = _str(env, args_json);
  char *s = NULL;
  const int r = dtb_module_action(o.s, prio, a.s, g.s, &s);
  _str_free(&o); _str_free(&a); _str_free(&g);
  return _json_status(env, r, s, status);
}

/* kind: 0 new, 1 duplicate; out[0] receives the new priority */
JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_instanceAdd(JNIEnv *env, jclass cls, jint kind, jstring op,
                                                   jint prio, jintArray out)
{
  jstr_t o = _str(env, op);
  int np = -1;
  const int r = kind == 0 ? dtb_instance_new(o.s, prio, &np) : dtb_instance_duplicate(o.s, prio, &np);
  _str_free(&o);
  const jint v = np;
  (*env)->SetIntArrayRegion(env, out, 0, 1, &v);
  return r;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_instanceDelete(JNIEnv *env, jclass cls, jstring op, jint prio)
{
  jstr_t o = _str(env, op);
  const int r = dtb_instance_delete(o.s, prio);
  _str_free(&o);
  return r;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_instanceMove(JNIEnv *env, jclass cls, jstring op, jint prio, jint dir)
{
  jstr_t o = _str(env, op);
  const int r = dtb_instance_move(o.s, prio, dir);
  _str_free(&o);
  return r;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_instanceRename(JNIEnv *env, jclass cls, jstring op, jint prio, jstring name)
{
  jstr_t o = _str(env, op), n = _str(env, name);
  const int r = dtb_instance_rename(o.s, prio, n.s ? n.s : "");
  _str_free(&o); _str_free(&n);
  return r;
}

/* what: 0 goto(arg), 1 compress, 2 seal, 3 undo, 4 redo */
JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_historyOp(JNIEnv *env, jclass cls, jint what, jint arg)
{
  switch(what)
  {
    case 0: return dtb_history_goto(arg);
    case 1: return dtb_history_compress();
    case 2: return dtb_history_seal();
    case 3: return dtb_undo();
    case 4: return dtb_redo();
    default: return DTB_E_ARG;
  }
}

/* Region into a Java byte array of exactly w*h*4 bytes. On ART a large
 * array lives in the non-moving large-object space, so the elements are
 * normally the array itself (no copy); *copied (array of one) tells. */
JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_renderRegionInto(JNIEnv *env, jclass cls, jint x, jint y, jint w, jint h,
                                                        jfloat scale, jbyteArray out, jbooleanArray copied)
{
  if(!out || w <= 0 || h <= 0) return DTB_E_ARG;
  if((*env)->GetArrayLength(env, out) != (jsize)((size_t)w * h * 4)) return DTB_E_ARG;
  jboolean is_copy = JNI_FALSE;
  jbyte *buf = (*env)->GetByteArrayElements(env, out, &is_copy);
  if(!buf) return DTB_E_ENGINE;
  const int r = dtb_render_region(x, y, w, h, scale, (uint8_t *)buf);
  // copy back only on success; a failed render leaves the array as it was
  (*env)->ReleaseByteArrayElements(env, out, buf, r == DTB_OK ? 0 : JNI_ABORT);
  if(copied) (*env)->SetBooleanArrayRegion(env, copied, 0, 1, &is_copy);
  return r;
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_exportWith(JNIEnv *env, jclass cls, jstring path, jstring options_json)
{
  jstr_t p = _str(env, path), o = _str(env, options_json);
  const int r = dtb_export(p.s, o.s);
  _str_free(&p); _str_free(&o);
  return r;
}

/* P3g (engine-api.md section 7): export with progress. The callback runs on
 * the calling (engine) thread inside dtb_export_ex, so the env of this call
 * is the right one. An exception thrown by the listener is cleared and
 * cancels the export. */
typedef struct { JNIEnv *env; jobject listener; jmethodID m; } _export_cb_t;

static void _export_progress(int step, int module, int modules, double fraction, void *user)
{
  _export_cb_t *c = user;
  if(!c || !c->listener || !c->m) return;
  (*c->env)->CallVoidMethod(c->env, c->listener, c->m, (jint)step, (jint)module, (jint)modules, (jdouble)fraction);
  if((*c->env)->ExceptionCheck(c->env))
  {
    (*c->env)->ExceptionClear(c->env);
    dtb_export_cancel();
  }
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_exportProgress(JNIEnv *env, jclass cls, jstring path, jstring options_json,
                                                      jobject listener)
{
  jstr_t p = _str(env, path), o = _str(env, options_json);
  _export_cb_t c = { env, listener, NULL };
  if(listener)
  {
    jclass lc = (*env)->GetObjectClass(env, listener);
    c.m = lc ? (*env)->GetMethodID(env, lc, "onProgress", "(IIID)V") : NULL;
    if(!c.m) (*env)->ExceptionClear(env);
  }
  const int r = dtb_export_ex(p.s, o.s, c.m ? _export_progress : NULL, &c);
  _str_free(&p); _str_free(&o);
  return r;
}

JNIEXPORT void JNICALL
Java_app_dtandroid_engine_NativeBridge_exportCancel(JNIEnv *env, jclass cls)
{
  dtb_export_cancel();
}

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_historySealCode(JNIEnv *env, jclass cls)
{
  return dtb_history_seal();
}

/* ---------------------------------------------------------------------- */
/* P1b part 1 (engine-api.md section 5): presets, styles, render times.    */
/* One entry point: what selects the call; s1..s3 and n1, n2 are its       */
/* arguments as listed in NativeBridge.kt. Returns the call's JSON, "{}"   */
/* for calls without a result, NULL on failure (status[0] = the code).     */
#define DTJ_PRESETS_LIST 0
#define DTJ_PRESET_APPLY 1
#define DTJ_PRESET_STORE 2
#define DTJ_PRESET_RENAME 3
#define DTJ_PRESET_DELETE 4
#define DTJ_PRESET_IMPORT 5
#define DTJ_PRESET_EXPORT 6
#define DTJ_STYLES_LIST 7
#define DTJ_STYLE_APPLY 8
#define DTJ_STYLES_IMPORT 9
#define DTJ_STYLE_DELETE 10
#define DTJ_STYLE_CREATE 11
#define DTJ_RENDER_TIMES 12
#define DTJ_HISTORY_COPY_ITEMS 13   /* P3j, engine-api.md 9 */
#define DTJ_HISTORY_COPY 14
#define DTJ_HISTORY_CLIPBOARD 15
#define DTJ_HISTORY_PASTE 16
#define DTJ_HISTORY_PASTE_FILES 17
#define DTJ_SHARED_SNAPSHOT 18      /* P3l, PLAN.md 4.5 (R11) */
#define DTJ_SHARED_APPLY 19
#define DTJ_SHARED_DELETE 20

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_p1b(JNIEnv *env, jclass cls, jint what, jstring js1, jstring js2,
                                           jstring js3, jint n1, jint n2, jintArray status)
{
  jstr_t a = _str(env, js1), b = _str(env, js2), c = _str(env, js3);
  char *s = NULL;
  int r = DTB_E_ARG;
  switch(what)
  {
    case DTJ_PRESETS_LIST: r = dtb_presets_list(a.s, n1, &s); break;
    case DTJ_PRESET_APPLY: r = dtb_preset_apply(a.s, n1, b.s); break;
    case DTJ_PRESET_STORE: r = dtb_preset_store(a.s, n1, b.s, c.s, n2); break;
    case DTJ_PRESET_RENAME: r = dtb_preset_rename(a.s, b.s, c.s); break;
    case DTJ_PRESET_DELETE: r = dtb_preset_delete(a.s, b.s); break;
    case DTJ_PRESET_IMPORT: r = dtb_preset_import(a.s, n2, &s); break;
    case DTJ_PRESET_EXPORT: r = dtb_preset_export(a.s, b.s, c.s, &s); break;
    case DTJ_STYLES_LIST: r = dtb_styles_list(&s); break;
    case DTJ_STYLE_APPLY: r = dtb_style_apply(a.s, b.s); break;
    case DTJ_STYLES_IMPORT: r = dtb_styles_import(a.s, n2, &s); break;
    case DTJ_STYLE_DELETE: r = dtb_style_delete(a.s); break;
    case DTJ_STYLE_CREATE: r = dtb_style_create(a.s, b.s, c.s, n2); break;
    case DTJ_RENDER_TIMES: r = dtb_render_times(&s); break;
    case DTJ_HISTORY_COPY_ITEMS: r = dtb_history_copy_items(&s); break;
    case DTJ_HISTORY_COPY: r = dtb_history_copy(a.s); break;
    case DTJ_HISTORY_CLIPBOARD: r = dtb_history_clipboard(&s); break;
    case DTJ_HISTORY_PASTE: r = dtb_history_paste(a.s); break;
    case DTJ_HISTORY_PASTE_FILES: r = dtb_history_paste_files(a.s, b.s, &s); break;
    case DTJ_SHARED_SNAPSHOT: r = dtb_shared_snapshot(&s); break;
    case DTJ_SHARED_APPLY: r = dtb_shared_apply(a.s, &s); break;
    case DTJ_SHARED_DELETE: r = dtb_shared_delete(a.s, b.s, n1, c.s); break;
    default: break;
  }
  _str_free(&a); _str_free(&b); _str_free(&c);
  if(r == DTB_OK && !s) s = strdup("{}");  // dtb_free is g_free, which is free() (GLib >= 2.46)
  return _json_status(env, r, s, status);
}

/* ---------------------------------------------------------------------- */
/* P1b part 2 (engine-api.md section 6): renders with options (6.1).       */

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_renderSizeEx(JNIEnv *env, jclass cls, jfloat scale, jstring options,
                                                    jintArray wh)
{
  jstr_t o = _str(env, options);
  int w = 0, h = 0;
  const int r = dtb_render_size_ex(scale, o.s, &w, &h);
  _str_free(&o);
  const jint v[2] = { w, h };
  (*env)->SetIntArrayRegion(env, wh, 0, 2, v);
  return r;
}

JNIEXPORT jbyteArray JNICALL
Java_app_dtandroid_engine_NativeBridge_renderPreviewEx(JNIEnv *env, jclass cls, jint max_size, jstring options,
                                                       jintArray wh, jintArray status)
{
  jstr_t o = _str(env, options);
  uint8_t *buf = NULL;
  int w = 0, h = 0;
  const int r = dtb_render_preview_ex(max_size, o.s, &buf, &w, &h);
  _str_free(&o);
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

JNIEXPORT jint JNICALL
Java_app_dtandroid_engine_NativeBridge_renderRegionIntoEx(JNIEnv *env, jclass cls, jint x, jint y, jint w, jint h,
                                                          jfloat scale, jstring options, jbyteArray out,
                                                          jbooleanArray copied)
{
  if(!out || w <= 0 || h <= 0) return DTB_E_ARG;
  if((*env)->GetArrayLength(env, out) != (jsize)((size_t)w * h * 4)) return DTB_E_ARG;
  jstr_t o = _str(env, options);
  jboolean is_copy = JNI_FALSE;
  jbyte *buf = (*env)->GetByteArrayElements(env, out, &is_copy);
  if(!buf)
  {
    _str_free(&o);
    return DTB_E_ENGINE;
  }
  const int r = dtb_render_region_ex(x, y, w, h, scale, o.s, (uint8_t *)buf);
  _str_free(&o);
  (*env)->ReleaseByteArrayElements(env, out, buf, r == DTB_OK ? 0 : JNI_ABORT);
  if(copied) (*env)->SetBooleanArrayRegion(env, copied, 0, 1, &is_copy);
  return r;
}

/* P1b part 2: one entry point for the JSON calls, as p1b above; what selects
 * the call, s1..s3 and n1, n2 are its arguments as listed in NativeBridge.kt */
#define DTJ2_COLOR_PICK 0
#define DTJ2_HISTOGRAM 1
#define DTJ2_COORDS 2
#define DTJ2_BLEND_GET 3
#define DTJ2_BLEND_SET 4
#define DTJ2_MASKS 5
#define DTJ2_MASK_CREATE 6
#define DTJ2_MASK_UPDATE 7
#define DTJ2_MASK_RENAME 8
#define DTJ2_MASK_DELETE 9
#define DTJ2_MASK_ATTACH 10
#define DTJ2_MASK_DETACH 11
#define DTJ2_MASK_MEMBER_SET 12
#define DTJ2_MASK_GROUP_CREATE 13
#define DTJ2_MASK_GROUP_SET 14

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_p1b2(JNIEnv *env, jclass cls, jint what, jstring js1, jstring js2,
                                            jstring js3, jint n1, jint n2, jintArray status)
{
  jstr_t a = _str(env, js1), b = _str(env, js2), c = _str(env, js3);
  char *s = NULL;
  int r = DTB_E_ARG;
  switch(what)
  {
    case DTJ2_COLOR_PICK: r = dtb_color_pick(a.s, n1, b.s, &s); break;
    case DTJ2_HISTOGRAM: r = dtb_histogram(b.s, &s); break;
    case DTJ2_COORDS: r = dtb_coords_transform(b.s, &s); break;
    case DTJ2_BLEND_GET: r = dtb_blend_get(a.s, n1, &s); break;
    case DTJ2_BLEND_SET: r = dtb_blend_set(a.s, n1, b.s); break;
    case DTJ2_MASKS: r = dtb_masks(&s); break;
    case DTJ2_MASK_CREATE: r = dtb_mask_create(a.s, b.s, c.s, &s); break;
    case DTJ2_MASK_UPDATE: r = dtb_mask_update(n2, b.s); break;
    case DTJ2_MASK_RENAME: r = dtb_mask_rename(n2, c.s); break;
    case DTJ2_MASK_DELETE: r = dtb_mask_delete(n2); break;
    case DTJ2_MASK_ATTACH: r = dtb_mask_attach(a.s, n1, n2, b.s); break;
    case DTJ2_MASK_DETACH: r = dtb_mask_detach(a.s, n1, n2); break;
    case DTJ2_MASK_MEMBER_SET: r = dtb_mask_member_set(a.s, n1, n2, b.s); break;
    case DTJ2_MASK_GROUP_CREATE: r = dtb_mask_group_create(b.s, c.s, &s); break;
    case DTJ2_MASK_GROUP_SET: r = dtb_mask_group_set(n2, b.s); break;
    default: break;
  }
  _str_free(&a); _str_free(&b); _str_free(&c);
  if(r == DTB_OK && !s) s = strdup("{}");
  return _json_status(env, r, s, status);
}

/* P3d stage 2a (engine-api.md 6.9): one entry point, as p1b2 with four ints */
#define DTJ3_GROUP_BEGIN 0
#define DTJ3_GROUP_END 1
#define DTJ3_MASK_ADD 2
#define DTJ3_MEMBER_MOVE 3
#define DTJ3_MEMBER_REMOVE 4
#define DTJ3_MEMBER_UPDATE 5
#define DTJ3_GROUP_WRAP 6
/* P3m (engine-api.md 6.10) */
#define DTJ3_RETOUCH 7
#define DTJ3_RETOUCH_ADD 8
#define DTJ3_RETOUCH_SET 9
#define DTJ3_RETOUCH_REMOVE 10
#define DTJ3_RETOUCH_SELECT 11
/* P3n (engine-api.md 6.11) */
#define DTJ3_ASHIFT 12

JNIEXPORT jstring JNICALL
Java_app_dtandroid_engine_NativeBridge_p3d(JNIEnv *env, jclass cls, jint what, jstring js1, jstring js2,
                                           jint n1, jint n2, jint n3, jint n4, jintArray status)
{
  jstr_t a = _str(env, js1), b = _str(env, js2);
  char *s = NULL;
  int r = DTB_E_ARG;
  switch(what)
  {
    case DTJ3_GROUP_BEGIN: r = dtb_history_group_begin(); break;
    case DTJ3_GROUP_END: r = dtb_history_group_end(n1); break;
    case DTJ3_MASK_ADD: r = dtb_mask_add(a.s, n1, b.s, &s); break;
    case DTJ3_MEMBER_MOVE: r = dtb_mask_member_move(n1, n2, n3, n4); break;
    case DTJ3_MEMBER_REMOVE: r = dtb_mask_member_remove(n1, n2); break;
    case DTJ3_MEMBER_UPDATE: r = dtb_mask_member_update(n1, n2, b.s); break;
    case DTJ3_GROUP_WRAP: r = dtb_mask_group_wrap(n1, b.s, a.s, &s); break;
    case DTJ3_RETOUCH: r = dtb_retouch(a.s, n1, &s); break;
    case DTJ3_RETOUCH_ADD: r = dtb_retouch_add(a.s, n1, b.s, &s); break;
    case DTJ3_RETOUCH_SET: r = dtb_retouch_shape_set(a.s, n1, n2, b.s); break;
    case DTJ3_RETOUCH_REMOVE: r = dtb_retouch_shape_remove(a.s, n1, n2); break;
    case DTJ3_RETOUCH_SELECT: r = dtb_retouch_select(a.s, n1, n2); break;
    case DTJ3_ASHIFT: r = dtb_ashift(a.s, n1, b.s, &s); break;
    default: break;
  }
  _str_free(&a); _str_free(&b);
  if(r == DTB_OK && !s) s = strdup("{}");
  return _json_status(env, r, s, status);
}
