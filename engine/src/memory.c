// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Memory the engine holds, and giving it back (brief P4a, engine-api.md 10).
 *
 * What darktable keeps between renders:
 * - the bridge's two pipelines (render.c): each holds a read lock on its
 *   input buffer in the mipmap cache (DT_MIPMAP_FULL for regions and full
 *   renders, DT_MIPMAP_F for the preview) and a cache of intermediate module
 *   outputs (develop/pixelpipe_cache.c, `allmem` bytes);
 * - the mipmap cache (common/mipmap_cache.c): decoded inputs of images
 *   (mip_full), their downscaled float copies (mip_f) and 8-bit thumbnails
 *   (mip_thumbs), each a dt_cache_t whose `cost` is its bytes.
 *
 * dtb_memory_trim level 1 drops what is rebuilt cheaply: the pipelines'
 * intermediate buffers and every mipmap entry not locked (other images,
 * thumbnails). Level 2 also frees the pipelines and with them the open
 * image's decoded input: the next render reads the RAW again. The history
 * (dtb_S.dev) is not touched at either level, so the edit stays as it is.
 */
#include "dtb_internal.h"

#include "common/cache.h"
#include "common/darktable.h"
#include "common/mipmap_cache.h"
#include "develop/pixelpipe_cache.h"

#include <unistd.h>
#include <stdio.h>
#include <glib.h>

static size_t _pipe_bytes(const dtb_pipe_t *p)
{
  return p->ready ? p->pipe.cache.allmem : 0;
}

static size_t _pipe_peak(const dtb_pipe_t *p)
{
  return p->ready ? MAX(p->pipe.cache.max_allmem, p->pipe.cache.allmem) : 0;
}

/* resident set size of the process from /proc/self/statm, in bytes; 0 if unreadable */
static size_t _rss(void)
{
  gchar *text = NULL;
  if(!g_file_get_contents("/proc/self/statm", &text, NULL, NULL)) return 0;
  unsigned long size = 0, resident = 0;
  const int n = sscanf(text, "%lu %lu", &size, &resident);
  g_free(text);
  if(n != 2) return 0;
  const long page = sysconf(_SC_PAGESIZE);
  return (size_t)resident * (size_t)(page > 0 ? page : 4096);
}

static void _member(JsonBuilder *b, const char *name, const size_t v)
{
  json_builder_set_member_name(b, name);
  json_builder_add_int_value(b, (gint64)v);
}

/* bytes held by a cache's entries (their buffers' sizes) and how many there
 * are. The image cache counts differently per level: mip_thumbs' cost is
 * bytes, mip_full's and mip_f's cost is entries (one each; their quota is
 * worker threads + 1 buffers, common/mipmap_cache.c dt_mipmap_cache_init),
 * so the bytes are summed from the entries themselves. */
static void _cache_members(JsonBuilder *b, const char *name, dt_cache_t *c)
{
  size_t bytes = 0;
  guint n = 0;
  dt_pthread_mutex_lock(&c->lock);
  GHashTableIter it;
  gpointer key, value;
  g_hash_table_iter_init(&it, c->hashtable);
  while(g_hash_table_iter_next(&it, &key, &value))
  {
    const dt_cache_entry_t *e = value;
    bytes += e->data_size;
    n++;
  }
  const size_t quota = c->cost_quota;
  dt_pthread_mutex_unlock(&c->lock);
  char k[64];
  snprintf(k, sizeof(k), "%s", name);
  _member(b, k, bytes);
  snprintf(k, sizeof(k), "%s_entries", name);
  _member(b, k, n);
  snprintf(k, sizeof(k), "%s_quota", name);
  _member(b, k, quota);
}

static char *_info(void)
{
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  _member(b, "pipe_full", _pipe_bytes(&dtb_S.full));
  _member(b, "pipe_full_peak", _pipe_peak(&dtb_S.full));
  _member(b, "pipe_preview", _pipe_bytes(&dtb_S.preview));
  _member(b, "pipe_preview_peak", _pipe_peak(&dtb_S.preview));
  json_builder_set_member_name(b, "pipes_ready");
  json_builder_add_int_value(b, (dtb_S.full.ready ? 1 : 0) + (dtb_S.preview.ready ? 1 : 0));
  dt_mipmap_cache_t *m = darktable.mipmap_cache;
  if(m)
  {
    _cache_members(b, "mipmap_full", &m->mip_full.cache);
    _cache_members(b, "mipmap_f", &m->mip_f.cache);
    _cache_members(b, "mipmap_thumbs", &m->mip_thumbs.cache);
  }
  _member(b, "rss", _rss());
  json_builder_end_object(b);
  return dtb__json_finish(b);
}

int dtb_memory_info(char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  *json = _info();
  return *json ? DTB_OK : dtb__err(DTB_E_ENGINE, "could not write the memory information");
}

/* dt_cache_gc expects the cache's lock held (common/cache.c calls it from
 * dt_cache_get with the lock taken) */
static void _gc(dt_cache_t *c)
{
  dt_pthread_mutex_lock(&c->lock);
  dt_cache_gc(c, 0.0f);
  dt_pthread_mutex_unlock(&c->lock);
}

static void _flush_pipe(dtb_pipe_t *p)
{
  if(!p->ready) return;
  // every cache line becomes invalid, then checkmem frees the invalid ones
  // (the two alternating lines below DT_PIPECACHE_MIN stay: the pipe needs them)
  dt_dev_pixelpipe_cache_flush(&p->pipe);
  dt_dev_pixelpipe_cache_checkmem(&p->pipe, TRUE);
}

int dtb_memory_trim(const int level, char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(level < 1 || level > 2) return dtb__err(DTB_E_ARG, "level is 1 or 2");
  if(level >= 2)
    dtb__pipes_free();  // releases the read locks on the open image's inputs; rebuilt at the next render
  else
  {
    _flush_pipe(&dtb_S.full);
    _flush_pipe(&dtb_S.preview);
  }
  dt_mipmap_cache_t *m = darktable.mipmap_cache;
  if(m)
  {
    // a locked entry (an input a pipeline still holds) is skipped by the gc
    _gc(&m->mip_thumbs.cache);
    _gc(&m->mip_f.cache);
    _gc(&m->mip_full.cache);
  }
  if(json) *json = _info();
  return DTB_OK;
}
