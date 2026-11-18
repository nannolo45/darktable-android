// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Version guard (PLAN.md 2.1): read an XMP sidecar's history without loading
 * the image, and report every entry this engine cannot represent faithfully.
 *
 * Why the bridge needs it: darktable itself does not refuse such an XMP. When
 * it loads the history, an entry whose module version is newer than the
 * engine's gets the module's default parameters (develop.c,
 * dt_dev_read_history_ext, "has unknown version ... using default
 * parameters"), and an entry for an unknown operation is dropped ("illegal
 * iop_order"). Writing the XMP back would then lose the edit silently.
 *
 * The XMP is parsed with libxml2 (a darktable dependency already). Exiv2
 * writes history entries as attributes of rdf:li (compact form); other tools
 * may write them as child elements. Both are read.
 */
#include "dtb_internal.h"

#include "common/darktable.h"
#include "develop/blend.h"
#include "develop/imageop.h"
#include "develop/masks.h"

#include <json-glib/json-glib.h>
#include <libxml/parser.h>
#include <libxml/tree.h>
#include <string.h>

#define NS_DT "http://darktable.sf.net/"
#define NS_RDF "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

static gboolean _is(const xmlNode *n, const char *ns, const char *name)
{
  return n && n->type == XML_ELEMENT_NODE && n->ns && n->ns->href
         && !strcmp((const char *)n->ns->href, ns)
         && !strcmp((const char *)n->name, name);
}

/* value of darktable:<name> on an rdf:li, as attribute or child element */
static gchar *_dt_prop(xmlNode *li, const char *name)
{
  xmlChar *v = xmlGetNsProp(li, (const xmlChar *)name, (const xmlChar *)NS_DT);
  if(v)
  {
    gchar *r = g_strdup((const char *)v);
    xmlFree(v);
    return r;
  }
  for(xmlNode *c = li->children; c; c = c->next)
    if(_is(c, NS_DT, name))
    {
      xmlChar *t = xmlNodeGetContent(c);
      gchar *r = g_strdup(t ? (const char *)t : "");
      if(t) xmlFree(t);
      return r;
    }
  return NULL;
}

static xmlNode *_find(xmlNode *n, const char *ns, const char *name)
{
  for(; n; n = n->next)
  {
    if(_is(n, ns, name)) return n;
    xmlNode *r = _find(n->children, ns, name);
    if(r) return r;
  }
  return NULL;
}

/* the rdf:li children of <darktable:name><rdf:Seq> */
static GList *_seq_items(xmlNode *root, const char *name)
{
  GList *items = NULL;
  xmlNode *h = _find(root, NS_DT, name);
  if(!h) return NULL;
  for(xmlNode *seq = h->children; seq; seq = seq->next)
  {
    if(!_is(seq, NS_RDF, "Seq") && !_is(seq, NS_RDF, "Bag")) continue;
    for(xmlNode *li = seq->children; li; li = li->next)
      if(_is(li, NS_RDF, "li")) items = g_list_append(items, li);
  }
  return items;
}

static void _problem(JsonBuilder *b, int num, const char *op, int version,
                     gboolean have_engine, int engine_version, const char *reason)
{
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "num");
  json_builder_add_int_value(b, num);
  json_builder_set_member_name(b, "operation");
  json_builder_add_string_value(b, op ? op : "");
  json_builder_set_member_name(b, "modversion");
  json_builder_add_int_value(b, version);
  json_builder_set_member_name(b, "engine_version");
  if(have_engine)
    json_builder_add_int_value(b, engine_version);
  else
    json_builder_add_null_value(b);
  json_builder_set_member_name(b, "reason");
  json_builder_add_string_value(b, reason);
  json_builder_end_object(b);
}

char *dtb__xmp_check(const char *xmp_path, int *n_problems)
{
  if(n_problems) *n_problems = -1;
  if(!xmp_path || !g_file_test(xmp_path, G_FILE_TEST_IS_REGULAR)) return NULL;

  xmlDoc *doc = xmlReadFile(xmp_path, NULL, XML_PARSE_NONET | XML_PARSE_NOWARNING
                                             | XML_PARSE_NOERROR);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  int problems = 0, entries = 0, xmp_version = -1;
  gboolean parsed = (doc != NULL);

  json_builder_set_member_name(b, "problems");
  json_builder_begin_array(b);
  if(doc)
  {
    xmlNode *root = xmlDocGetRootElement(doc);

    // xmp_version is an attribute of rdf:Description in exiv2's compact form
    xmlNode *desc = _find(root, NS_RDF, "Description");
    for(xmlNode *d = desc; d; d = d->next)
    {
      if(!_is(d, NS_RDF, "Description")) continue;
      gchar *v = _dt_prop(d, "xmp_version");
      if(v)
      {
        xmp_version = atoi(v);
        g_free(v);
        break;
      }
    }

    const int engine_blend = dt_develop_blend_version();
    GList *items = _seq_items(root, "history");
    for(GList *l = items; l; l = g_list_next(l))
    {
      xmlNode *li = l->data;
      gchar *op = _dt_prop(li, "operation");
      gchar *mv = _dt_prop(li, "modversion");
      gchar *bv = _dt_prop(li, "blendop_version");
      gchar *nm = _dt_prop(li, "num");
      const int num = nm ? atoi(nm) : entries;
      const int modversion = mv ? atoi(mv) : 0;
      entries++;

      const dt_iop_module_so_t *so = op ? dt_iop_get_module_so(op) : NULL;
      if(!so)
      {
        _problem(b, num, op, modversion, FALSE, 0, "unknown_operation");
        problems++;
      }
      else
      {
        const int engine_version = so->version();
        if(modversion > engine_version)
        {
          _problem(b, num, op, modversion, TRUE, engine_version, "newer_module_version");
          problems++;
        }
        if(bv && atoi(bv) > engine_blend)
        {
          _problem(b, num, op, atoi(bv), TRUE, engine_blend, "newer_blend_version");
          problems++;
        }
      }
      g_free(op);
      g_free(mv);
      g_free(bv);
      g_free(nm);
    }
    g_list_free(items);

    // masks: a form written by a newer masks version cannot be read either
    const int engine_masks = dt_masks_version();
    GList *masks = _seq_items(root, "masks_history");
    for(GList *l = masks; l; l = g_list_next(l))
    {
      gchar *v = _dt_prop(l->data, "mask_version");
      gchar *nm = _dt_prop(l->data, "mask_num");
      if(v && atoi(v) > engine_masks)
      {
        _problem(b, nm ? atoi(nm) : -1, "masks", atoi(v), TRUE, engine_masks,
                 "newer_mask_version");
        problems++;
      }
      g_free(v);
      g_free(nm);
    }
    g_list_free(masks);
    xmlFreeDoc(doc);
  }
  json_builder_end_array(b);

  json_builder_set_member_name(b, "ok");
  json_builder_add_boolean_value(b, parsed && problems == 0);
  json_builder_set_member_name(b, "parsed");
  json_builder_add_boolean_value(b, parsed);
  json_builder_set_member_name(b, "xmp_version");
  json_builder_add_int_value(b, xmp_version);
  json_builder_set_member_name(b, "entries");
  json_builder_add_int_value(b, entries);
  json_builder_end_object(b);

  JsonGenerator *gen = json_generator_new();
  JsonNode *rootn = json_builder_get_root(b);
  json_generator_set_root(gen, rootn);
  gchar *out = json_generator_to_data(gen, NULL);
  json_node_free(rootn);
  g_object_unref(gen);
  g_object_unref(b);

  if(n_problems) *n_problems = parsed ? problems : -1;
  return out;
}
