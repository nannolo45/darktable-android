// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: module actions added by P1b (docs/design/engine-api.md
 * section 5.4; the calls P2j's report, section 6, waits for):
 *   colorin: list_profiles
 *   borders: set_aspect {aspect}
 *   lens:    use_latest_md
 * (denoiseprofile's "current", white balance's tuning and preset position
 * and the lenses' apertures extend existing actions in actions.c.)
 *
 * Why module actions and not a "write unchecked" flag on param_set: the
 * values desktop writes outside a field's limits are fixed values of one
 * callback each; an action writes exactly those and nothing else, while a
 * flag would open every field to any value.
 */
#include "dtb_internal.h"

#include "common/colorspaces.h"
#include "common/image_cache.h"

#include <strings.h>

// the camera matrices colorin.c lists (static tables, included the way
// iop/colorin.c includes them)
#include "common/colormatrices.c"

static int _need_writable2(void)
{
  if(dtb_S.read_only)
    return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  return DTB_OK;
}

static void _commit2(dt_iop_module_t *m, const void *params)
{
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  memcpy(m->params, params, m->params_size);
  dtb__history_add(m, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
}

/* ------------------------------------------------------------------ */
/* colorin                                                            */

static void _profile_entry(JsonBuilder *b, dt_introspection_field_t *type_field, const char *kind,
                           const dt_colorspaces_color_profile_type_t type, const char *filename, const char *name,
                           const gboolean current)
{
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "kind");
  json_builder_add_string_value(b, kind);
  json_builder_set_member_name(b, "type");
  const char *tn = dt_introspection_get_enum_name(type_field, type);
  if(tn) json_builder_add_string_value(b, tn);
  else json_builder_add_int_value(b, type);
  json_builder_set_member_name(b, "filename");
  json_builder_add_string_value(b, filename ? filename : "");
  json_builder_set_member_name(b, "name");
  gchar *v = g_utf8_make_valid(name ? name : "", -1);
  json_builder_add_string_value(b, v);
  g_free(v);
  json_builder_set_member_name(b, "current");
  json_builder_add_boolean_value(b, current);
  json_builder_end_object(b);
}

static gboolean _same_profile(const int type, const char *filename, const int ptype, const char *pfile)
{
  if(type != ptype) return FALSE;
  // only file profiles are told apart by their file
  if(type == DT_COLORSPACE_FILE) return !strcmp(filename ? filename : "", pfile ? pfile : "");
  return TRUE;
}

static int _act_colorin(dt_iop_module_t *m, const char *action, JsonObject *a, char **res)
{
  (void)a;
  if(strcmp(action, "list_profiles")) return dtb__err(DTB_E_NOT_FOUND, "colorin has no action '%s'", action);
  int r = dtb__full_pipe();  // the image's matrices are known once it is loaded
  if(r) return r;
  dtb_field_ref_t rt, rf, rtw, rfw;
  if(dtb__field_resolve(m, m->params, "type", &rt) || dtb__field_resolve(m, m->params, "filename", &rf)
     || dtb__field_resolve(m, m->params, "type_work", &rtw) || dtb__field_resolve(m, m->params, "filename_work", &rfw))
    return dtb__err(DTB_E_ENGINE, "colorin parameters not found (upstream changed?)");
  const int cur_type = *(const int *)rt.p, cur_work = *(const int *)rtw.p;
  const char *cur_file = (const char *)rf.p, *cur_wfile = (const char *)rfw.p;

  const dt_image_t *img = dt_image_cache_get(dtb_S.imgid, 'r');
  if(!img) return dtb__err(DTB_E_ENGINE, "image not in cache");
  const gboolean has_icc = img->profile != NULL;
  const gboolean has_dng_look = img->profile_hsm_data != NULL;
  dt_image_cache_read_release(img);
  const dt_image_t *st = &dtb_S.dev.image_storage;

  // update_profile_list() (iop/colorin.c): the image's own profiles first
  int types[8], n = 0;
  if(has_icc) types[n++] = DT_COLORSPACE_EMBEDDED_ICC;
  if(dt_is_valid_colormatrix(st->d65_color_matrix[0]))
  {
    types[n++] = DT_COLORSPACE_EMBEDDED_MATRIX;
    if(!dt_is_valid_colormatrix(st->dng_forward_matrix[0]) && has_dng_look) types[n++] = DT_COLORSPACE_DNG_LOOK;
  }
  if(dt_is_valid_colormatrix(st->dng_forward_matrix[0])) types[n++] = DT_COLORSPACE_FORWARD_MATRIX;
  if(dt_is_valid_colormatrix(st->adobe_XYZ_to_CAM[0][0]) && !(st->flags & DT_IMAGE_4BAYER))
    types[n++] = DT_COLORSPACE_STANDARD_MATRIX;
  for(int k = 0; k < dt_profiled_colormatrix_cnt; k++)
    if(!strcasecmp(st->camera_makermodel, dt_profiled_colormatrices[k].makermodel))
    {
      types[n++] = DT_COLORSPACE_ENHANCED_MATRIX;
      break;
    }
  for(int k = 0; k < dt_vendor_colormatrix_cnt; k++)
    if(!strcmp(st->camera_makermodel, dt_vendor_colormatrices[k].makermodel))
    {
      types[n++] = DT_COLORSPACE_VENDOR_MATRIX;
      break;
    }
  for(int k = 0; k < dt_alternate_colormatrix_cnt; k++)
    if(!strcmp(st->camera_makermodel, dt_alternate_colormatrices[k].makermodel))
    {
      types[n++] = DT_COLORSPACE_ALTERNATE_MATRIX;
      break;
    }

  JsonBuilder *b = json_builder_new();
  json_builder_begin_array(b);
  gboolean found_in = FALSE, found_work = FALSE;
  for(int i = 0; i < n; i++)
  {
    const gboolean cur = !found_in && _same_profile(cur_type, cur_file, types[i], "");
    found_in |= cur;
    _profile_entry(b, rt.f, "input", types[i], "", dt_colorspaces_get_name(types[i], ""), cur);
  }
  for(GList *l = darktable.color_profiles->profiles; l; l = g_list_next(l))
  {
    const dt_colorspaces_color_profile_t *p = l->data;
    if(p->in_pos < 0) continue;
    const gboolean cur = !found_in && _same_profile(cur_type, cur_file, p->type, p->filename);
    found_in |= cur;
    _profile_entry(b, rt.f, "input", p->type, p->filename, p->name, cur);
  }
  for(GList *l = darktable.color_profiles->profiles; l; l = g_list_next(l))
  {
    const dt_colorspaces_color_profile_t *p = l->data;
    if(p->work_pos < 0) continue;
    const gboolean cur = !found_work && _same_profile(cur_work, cur_wfile, p->type, p->filename);
    found_work |= cur;
    _profile_entry(b, rtw.f, "work", p->type, p->filename, p->name, cur);
  }
  json_builder_end_array(b);
  *res = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* borders (framing)                                                  */

static int _act_borders(dt_iop_module_t *m, const char *action, JsonObject *a, char **res)
{
  if(strcmp(action, "set_aspect")) return dtb__err(DTB_E_NOT_FOUND, "borders has no action '%s'", action);
  if(_need_writable2()) return DTB_E_READ_ONLY;
  JsonNode *v = a ? json_object_get_member(a, "aspect") : NULL;
  if(!v || JSON_NODE_TYPE(v) != JSON_NODE_VALUE
     || (json_node_get_value_type(v) != G_TYPE_DOUBLE && json_node_get_value_type(v) != G_TYPE_INT64))
    return dtb__err(DTB_E_TYPE, "set_aspect takes {aspect: number}");
  const double aspect = json_node_get_double(v);
  void *tmp = dtb__memdup(m->params, m->params_size);
  dtb_field_ref_t r;
  if(dtb__field_resolve(m, tmp, "aspect", &r) || r.f->header.type != DT_INTROSPECTION_TYPE_FLOAT)
  {
    g_free(tmp);
    return dtb__err(DTB_E_ENGINE, "borders has no float 'aspect' (upstream changed?)");
  }
  // iop/borders.c _aspect_ratios[]: DT_IOP_BORDERS_ASPECT_IMAGE_VALUE (0)
  // and DT_IOP_BORDERS_ASPECT_CONSTANT_VALUE (-1) are written by its list,
  // outside the field's $MIN 1 .. $MAX 3
  if(aspect != 0.0 && aspect != -1.0 && (aspect < r.f->Float.Min || aspect > r.f->Float.Max))
  {
    g_free(tmp);
    return dtb__err(DTB_E_RANGE, "aspect %g: 0 (image), -1 (constant border) or %g..%g", aspect,
                    (double)r.f->Float.Min, (double)r.f->Float.Max);
  }
  *(float *)r.p = (float)aspect;
  _commit2(m, tmp);
  g_free(tmp);
  *res = g_strdup("{}");
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* lens: "use latest algorithm"                                       */

static int _act_lens_md(dt_iop_module_t *m, char **res)
{
  if(_need_writable2()) return DTB_E_READ_ONLY;
  void *tmp = dtb__memdup(m->params, m->params_size);
  dtb_field_ref_t rv, rs;
  if(dtb__field_resolve(m, tmp, "md_version", &rv) || rv.f->header.type != DT_INTROSPECTION_TYPE_ENUM
     || dtb__field_resolve(m, tmp, "scale_md_v1", &rs) || rs.f->header.type != DT_INTROSPECTION_TYPE_FLOAT)
  {
    g_free(tmp);
    return dtb__err(DTB_E_ENGINE, "lens has no md_version / scale_md_v1 (upstream changed?)");
  }
  int v2 = -1;
  for(dt_introspection_type_enum_tuple_t *t = rv.f->Enum.values; t && t->name; t++)
    if(!strcmp(t->name, "DT_IOP_LENS_EMBEDDED_METADATA_VERSION_2")) v2 = t->value;
  if(v2 < 0)
  {
    g_free(tmp);
    return dtb__err(DTB_E_ENGINE, "lens has no DT_IOP_LENS_EMBEDDED_METADATA_VERSION_2 (upstream changed?)");
  }
  // _use_latest_md_algo_callback() (iop/lens.cc)
  *(int *)rv.p = v2;
  *(float *)rs.p = 0.0f;
  _commit2(m, tmp);
  g_free(tmp);
  *res = g_strdup("{}");
  return DTB_OK;
}

/* the actions of this file; DTB_E_NOT_FOUND + 1 when it has none for the
 * module, so actions.c tries its own */
int dtb__module_action2(dt_iop_module_t *m, const char *action, JsonObject *args, char **result)
{
  if(dt_iop_module_is(m, "colorin")) return _act_colorin(m, action, args, result);
  if(dt_iop_module_is(m, "borders")) return _act_borders(m, action, args, result);
  if(dt_iop_module_is(m, "lens") && !strcmp(action, "use_latest_md")) return _act_lens_md(m, result);
  return 1;
}
