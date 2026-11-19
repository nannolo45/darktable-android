// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Layout probe (brief P2d): a darktable "lib" plugin used only on Linux, to show
 * what an unmodified darktable stores and exports for module layouts. Inert unless
 * DT_LAYOUT_PROBE_OUT is set. Once the lighttable is shown it
 *   1. writes every preset of the "modulegroups" lib module from darktable's
 *      database (name, write protection, op_version, op_params in hex) to
 *      <out>/presets.jsonl;
 *   2. if DT_LAYOUT_PROBE_EXPORT=<name>:<file> is set: stores the layout string
 *      in <file> as a user layout <name> through dt_lib_presets_add (what saving a
 *      layout does) and exports it with dt_presets_save_to_file under the name
 *      preferences -> presets -> export uses ("modulegroups_<name>");
 *   3. if DT_LAYOUT_PROBE_IMPORT=<file>[:<file>...] is set: imports each file with
 *      dt_presets_import_from_file (preferences -> presets -> import) and writes the
 *      presets again to <out>/presets-after-import.jsonl;
 * then quits darktable.
 */
#include "common/darktable.h"
#include "common/database.h"
#include "common/presets.h"
#include "control/control.h"
#include "libs/lib.h"
#include "libs/lib_api.h"

#include <glib/gstdio.h>
#include <sqlite3.h>
#include <stdio.h>
#include <string.h>

DT_MODULE(1)

const char *name(dt_lib_module_t *self) { return "layout probe"; }
dt_view_type_flags_t views(dt_lib_module_t *self) { return DT_VIEW_LIGHTTABLE; }
uint32_t container(dt_lib_module_t *self) { return DT_UI_CONTAINER_PANEL_LEFT_CENTER; }
gboolean expandable(dt_lib_module_t *self) { return FALSE; }
int position(const dt_lib_module_t *self) { return 1; }

static gchar *_out = NULL;

static void _hex(FILE *f, const unsigned char *p, int n)
{
  for(int i = 0; i < n; i++) fprintf(f, "%02x", p[i]);
}

static void _dump(const char *file)
{
  gchar *path = g_build_filename(_out, file, NULL);
  FILE *f = g_fopen(path, "w");
  g_free(path);
  if(!f) return;
  sqlite3_stmt *stmt;
  sqlite3_prepare_v2(dt_database_get(darktable.db),
                     "SELECT name, writeprotect, op_version, op_params, autoapply FROM data.presets"
                     " WHERE operation = 'modulegroups' ORDER BY writeprotect DESC, name, rowid",
                     -1, &stmt, NULL);
  while(sqlite3_step(stmt) == SQLITE_ROW)
  {
    const unsigned char *name = sqlite3_column_text(stmt, 0);
    fprintf(f, "{\"name_hex\":\"");
    _hex(f, name, strlen((const char *)name));
    fprintf(f, "\",\"writeprotect\":%d,\"op_version\":%d,\"autoapply\":%d,\"params_hex\":\"",
            sqlite3_column_int(stmt, 1), sqlite3_column_int(stmt, 2), sqlite3_column_int(stmt, 4));
    _hex(f, sqlite3_column_blob(stmt, 3), sqlite3_column_bytes(stmt, 3));
    fprintf(f, "\"}\n");
  }
  sqlite3_finalize(stmt);
  fclose(f);
}

static gboolean _run(gpointer user_data)
{
  _dump("presets.jsonl");

  const char *exp = g_getenv("DT_LAYOUT_PROBE_EXPORT");
  if(exp && *exp)
  {
    gchar **nf = g_strsplit(exp, ":", 2);
    gchar *text = NULL;
    gsize len = 0;
    if(g_strv_length(nf) == 2 && g_file_get_contents(nf[1], &text, &len, NULL))
    {
      dt_lib_presets_add(nf[0], "modulegroups", 1, text, strlen(text), FALSE, 0);
      sqlite3_stmt *stmt;
      sqlite3_prepare_v2(dt_database_get(darktable.db),
                         "SELECT rowid FROM data.presets WHERE operation = 'modulegroups' AND name = ?1",
                         -1, &stmt, NULL);
      sqlite3_bind_text(stmt, 1, nf[0], -1, SQLITE_TRANSIENT);
      if(sqlite3_step(stmt) == SQLITE_ROW)
      {
        gchar *fname = g_strdup_printf("modulegroups_%s", nf[0]);
        dt_presets_save_to_file(sqlite3_column_int(stmt, 0), fname, _out);
        g_free(fname);
      }
      sqlite3_finalize(stmt);
      g_free(text);
    }
    else
      fprintf(stderr, "[layout probe] DT_LAYOUT_PROBE_EXPORT must be <name>:<readable file>\n");
    g_strfreev(nf);
  }

  const char *imp = g_getenv("DT_LAYOUT_PROBE_IMPORT");
  if(imp && *imp)
  {
    gchar *path = g_build_filename(_out, "import-results.txt", NULL);
    FILE *f = g_fopen(path, "w");
    g_free(path);
    gchar **files = g_strsplit(imp, ":", -1);
    for(gchar **p = files; *p; p++)
    {
      const gboolean ok = dt_presets_import_from_file(*p);
      if(f) fprintf(f, "%s %s\n", ok ? "imported" : "failed", *p);
    }
    g_strfreev(files);
    if(f) fclose(f);
    _dump("presets-after-import.jsonl");
  }

  gchar *done = g_build_filename(_out, "done", NULL);
  g_file_set_contents(done, "done\n", -1, NULL);
  g_free(done);
  dt_control_quit();
  return G_SOURCE_REMOVE;
}

void gui_init(dt_lib_module_t *self)
{
  self->widget = gtk_label_new("");
  self->data = NULL;
  const char *out = g_getenv("DT_LAYOUT_PROBE_OUT");
  if(out && *out)
  {
    _out = g_strdup(out);
    g_mkdir_with_parents(_out, 0755);
  }
}

void gui_cleanup(dt_lib_module_t *self)
{
  g_free(_out);
  _out = NULL;
}

void view_enter(dt_lib_module_t *self, dt_view_t *old_view, dt_view_t *new_view)
{
  static gboolean started = FALSE;
  if(!_out || started) return;
  started = TRUE;
  g_timeout_add(1500, _run, self);
}
