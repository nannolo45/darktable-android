// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * C29 reference (brief P3j): desktop's own copy and paste, on plain library
 * images, without the bridge's sessions, clipboard image or undo. Two photos
 * are imported with their XMP as darktable-cli does; upstream's
 * dt_history_copy_and_paste_on_image() pastes A onto C in append mode (the
 * lighttable's paste job: dt_history_paste with merge), either
 *   - "all": desktop's "copy" (dt_history_copy: ops NULL, full_copy FALSE,
 *     copy_iop_order TRUE), or
 *   - "<op> <priority>": desktop's selective copy (dt_history_copy_parts:
 *     full_copy TRUE) of the newest list entry of that instance (the list of
 *     gui/hist_dialog.c, dt_history_get_items);
 * and C's XMP is written. The result is what desktop writes; run_tests.sh
 * compares it with the bridge's (p3j_xmp_compare.py).
 *
 *   dtb_p3jref <workdir> <rawA> <xmpA> <rawC> <xmpC> <out.xmp> all
 *   dtb_p3jref <workdir> <rawA> <xmpA> <rawC> <xmpC> <out.xmp> <op> <priority>
 */
#include "dtb_internal.h"

#include "common/database.h"
#include "common/debug.h"
#include "common/exif.h"
#include "common/film.h"
#include "common/history.h"
#include "common/image.h"
#include "common/image_cache.h"
#include "develop/develop.h"

#include <glib/gstdio.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MUST(x)                                                                          \
  do {                                                                                   \
    const int _r = (x);                                                                  \
    if(_r) { fprintf(stdout, "FAIL %s: %d %s\n", #x, _r, dtb_last_error()); exit(1); }  \
  } while(0)

static void engine_start(const char *workdir)
{
  const char *prefix = getenv("DT_PREFIX");
  if(!prefix) { fprintf(stdout, "FAIL DT_PREFIX not set\n"); exit(1); }
  g_mkdir(workdir, 0700);
  gchar *library = g_build_filename(workdir, "library.db", NULL);
  g_unlink(library);
  gchar *configdir = g_build_filename(workdir, "config", NULL);
  gchar *cachedir = g_build_filename(workdir, "cache", NULL);
  gchar *tmpdir = g_build_filename(workdir, "tmp", NULL);
  gchar *datadir = g_build_filename(prefix, "share/darktable", NULL);
  gchar *moduledir = g_build_filename(prefix, "lib/darktable", NULL);
  g_mkdir(configdir, 0700);
  const char *level = getenv("DTB_TEST_RESOURCELEVEL");
  if(level)
  {
    gchar *rc = g_build_filename(configdir, "darktablerc", NULL);
    gchar *text = g_strdup_printf("resourcelevel=%s\n", level);
    g_file_set_contents(rc, text, -1, NULL);
    g_free(text);
    g_free(rc);
  }
  dtb_paths_t p = { configdir, cachedir, datadir, moduledir, tmpdir, library };
  MUST(dtb_init(&p, 0));
}

/* as darktable-cli: the image into the library, its XMP read */
static dt_imgid_t import(const char *raw, const char *xmp)
{
  gchar *dir = g_path_get_dirname(raw);
  dt_film_t film;
  dt_film_init(&film);
  const dt_filmid_t filmid = dt_film_new(&film, dir);
  dt_film_cleanup(&film);
  g_free(dir);
  const dt_imgid_t id = dt_image_import(filmid, raw, TRUE, FALSE);
  if(!dt_is_valid_imgid(id)) { fprintf(stdout, "FAIL cannot import %s\n", raw); exit(1); }
  dt_image_t *img = dt_image_cache_get(id, 'w');
  const gboolean failed = dt_exif_xmp_read(img, xmp, FALSE);
  dt_image_cache_write_release(img, DT_IMAGE_CACHE_RELAXED);
  if(failed) { fprintf(stdout, "FAIL cannot read %s\n", xmp); exit(1); }
  return id;
}

static int one_int(const char *sql, const dt_imgid_t id)
{
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db), sql, -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, id);
  const int v = sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int(stmt, 0) : -1;
  sqlite3_finalize(stmt);
  return v;
}

static void print_library(const char *tag, const dt_imgid_t id)
{
  fprintf(stdout, "RESULT %s history_end %d, rows %d, mask rows %d\n", tag,
          one_int("SELECT history_end FROM main.images WHERE id = ?1", id),
          one_int("SELECT COUNT(*) FROM main.history WHERE imgid = ?1", id),
          one_int("SELECT COUNT(*) FROM main.masks_history WHERE imgid = ?1", id));
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT num, operation, multi_priority, multi_name, enabled FROM main.history"
                              " WHERE imgid = ?1 ORDER BY num", -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, id);
  while(sqlite3_step(stmt) == SQLITE_ROW)
    fprintf(stdout, "RESULT %s row %d %s/%d '%s' %d\n", tag, sqlite3_column_int(stmt, 0),
            (const char *)sqlite3_column_text(stmt, 1), sqlite3_column_int(stmt, 2),
            sqlite3_column_text(stmt, 3) ? (const char *)sqlite3_column_text(stmt, 3) : "", sqlite3_column_int(stmt, 4));
  sqlite3_finalize(stmt);
}

int main(int argc, char **argv)
{
  setvbuf(stdout, NULL, _IOLBF, 0);
  // diagnosis (C29 on arw): "write" = A's history loaded and written back as a bridge session does
  // (dt_dev_load_image, dt_dev_write_history); "hop" = through a clipboard image as the bridge's copy does
  gboolean do_write = FALSE, do_hop = FALSE;
  while(argc > 9 && (!strcmp(argv[argc - 1], "write") || !strcmp(argv[argc - 1], "hop")))
  {
    if(!strcmp(argv[argc - 1], "write")) do_write = TRUE;
    else do_hop = TRUE;
    argc--;
  }
  if(argc != 8 && argc != 9)
  {
    fprintf(stderr, "usage: dtb_p3jref <workdir> <rawA> <xmpA> <rawC> <xmpC> <out.xmp> all | <op> <priority>\n");
    return 2;
  }
  engine_start(argv[1]);
  dt_imgid_t a = import(argv[2], argv[3]);
  const dt_imgid_t c = import(argv[4], argv[5]);
  if(a == c) { fprintf(stdout, "FAIL A and C are one image\n"); return 1; }
  if(do_write)
  {
    dt_develop_t d;
    dt_dev_init(&d, FALSE);
    dt_dev_load_image(&d, a);
    dt_dev_pop_history_items_ext(&d, d.history_end);
    dt_dev_write_history(&d);
    dt_dev_cleanup(&d);
    print_library("A written back", a);
  }
  if(do_hop)
  {
    const dt_imgid_t h = dt_image_duplicate_with_version(a, 32000);
    if(!dt_history_copy_and_paste_on_image(a, h, FALSE, NULL, TRUE, TRUE, FALSE))
    {
      fprintf(stdout, "FAIL copy into the clipboard image\n");
      return 1;
    }
    print_library("clipboard image", h);
    a = h;
  }
  GList *ops = NULL;
  gboolean full = FALSE;
  if(argc == 9)
  {
    const char *op = argv[7];
    const int prio = atoi(argv[8]);
    int best = -1;
    GList *items = dt_history_get_items(a, FALSE, TRUE, FALSE);
    for(GList *l = items; l; l = g_list_next(l))
    {
      const dt_history_item_t *it = l->data;
      if(strcmp(it->op, op)) continue;
      sqlite3_stmt *stmt;
      DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                                  "SELECT multi_priority FROM main.history WHERE imgid = ?1 AND num = ?2", -1, &stmt,
                                  NULL);
      DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, a);
      DT_DEBUG_SQLITE3_BIND_INT(stmt, 2, it->num);
      const int p = sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int(stmt, 0) : -1;
      sqlite3_finalize(stmt);
      if(p == prio && (int)it->num > best) best = it->num;
    }
    g_list_free_full(items, dt_history_item_free);
    if(best < 0) { fprintf(stdout, "FAIL no list entry for %s/%d\n", op, prio); return 1; }
    fprintf(stdout, "RESULT selective copy of %s/%d: history num %d\n", op, prio, best);
    ops = g_list_append(NULL, GINT_TO_POINTER(best));
    full = TRUE;
  }
  print_library("before", c);
  if(!dt_history_copy_and_paste_on_image(a, c, TRUE, ops, TRUE, full, FALSE))
  {
    fprintf(stdout, "FAIL dt_history_copy_and_paste_on_image returned FALSE\n");
    return 1;
  }
  print_library("after", c);
  if(dt_exif_xmp_write(c, argv[6], TRUE)) { fprintf(stdout, "FAIL cannot write %s\n", argv[6]); return 1; }
  g_list_free(ops);
  dtb_shutdown();
  fprintf(stdout, "RESULT exit 0\n");
  return 0;
}
