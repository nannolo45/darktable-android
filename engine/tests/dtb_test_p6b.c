// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of brief P6b, part of the test driver: dtb_test.c includes this file
 * after dtb_test_p6a.c.
 *
 *   lut3dfiles <raw> <xmp> <outdir>  C44: the LUT folder given to the bridge
 *                                    (dtb_lut_folder_set, not darktablerc),
 *                                    files chosen by their relative path:
 *                                    a .cube, a .3dl and a Hald .png made
 *                                    here (the same warm transform, size 4),
 *                                    each written to an XMP for darktable-cli
 *                                    with the same folder (p6b_cube, p6b_3dl,
 *                                    p6b_png); refusals (relative folder,
 *                                    "..", absolute outside, .gmz, .Cube);
 *                                    a missing file renders as the module
 *                                    switched off and comes back when the file
 *                                    is there again and the folder is set
 *                                    again (p6b_missing for darktable-cli,
 *                                    whose file is missing too)
 */

/* the warm transform every format carries: out = a + b * in per channel */
static void p6b_warm(double r, double g, double b, double out[3])
{
  out[0] = 0.06 + 0.94 * r;
  out[1] = 0.03 + 0.93 * g;
  out[2] = 0.85 * b;
}

#define P6B_N 4

static int p6b_write(const char *path, const char *text)
{
  gchar *dir = g_path_get_dirname(path);
  g_mkdir_with_parents(dir, 0755);
  g_free(dir);
  return g_file_set_contents(path, text, -1, NULL) ? 0 : 1;
}

/* .cube: red fastest (iop/lut3d.c _calculate_clut_cube) */
static char *p6b_cube(int identity)
{
  GString *s = g_string_new("TITLE \"p6b\"\nLUT_3D_SIZE 4\n");
  for(int b = 0; b < P6B_N; b++)
    for(int g = 0; g < P6B_N; g++)
      for(int r = 0; r < P6B_N; r++)
      {
        double o[3] = { r / 3.0, g / 3.0, b / 3.0 };
        if(!identity) p6b_warm(r / 3.0, g / 3.0, b / 3.0, o);
        g_string_append_printf(s, "%.6f %.6f %.6f\n", o[0], o[1], o[2]);
      }
  return g_string_free(s, FALSE);
}

/* .3dl: a shaper line of N values (the cube's size), then N^3 lines of
 * 10-bit integers with blue fastest (_calculate_clut_3dl) */
static char *p6b_3dl(void)
{
  GString *s = g_string_new("0 341 682 1023\n");
  for(int r = 0; r < P6B_N; r++)
    for(int g = 0; g < P6B_N; g++)
      for(int b = 0; b < P6B_N; b++)
      {
        double o[3];
        p6b_warm(r / 3.0, g / 3.0, b / 3.0, o);
        g_string_append_printf(s, "%d %d %d\n", (int)lround(o[0] * 1023), (int)lround(o[1] * 1023),
                               (int)lround(o[2] * 1023));
      }
  return g_string_free(s, FALSE);
}

static uint32_t p6b_crc(const uint8_t *p, size_t n, uint32_t c)
{
  c = ~c;
  for(size_t i = 0; i < n; i++)
  {
    c ^= p[i];
    for(int k = 0; k < 8; k++) c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1)));
  }
  return ~c;
}

static void p6b_be32(GByteArray *a, uint32_t v)
{
  const uint8_t b[4] = { v >> 24, v >> 16, v >> 8, v };
  g_byte_array_append(a, b, 4);
}

static void p6b_chunk(GByteArray *png, const char *type, const uint8_t *data, uint32_t n)
{
  p6b_be32(png, n);
  GByteArray *td = g_byte_array_new();
  g_byte_array_append(td, (const uint8_t *)type, 4);
  if(n) g_byte_array_append(td, data, n);
  g_byte_array_append(png, td->data, td->len);
  p6b_be32(png, p6b_crc(td->data, td->len, 0));
  g_byte_array_free(td, TRUE);
}

/* Hald CLUT level 2: an 8 x 8 RGB image holding a cube of size 4, red
 * fastest (_calculate_clut_haldclut). Written as a PNG with one stored
 * (uncompressed) deflate block, so no zlib is needed here. */
static int p6b_png(const char *path)
{
  const int w = 8, h = 8;
  GByteArray *raw = g_byte_array_new();  // filter byte 0 + RGB per row
  for(int y = 0; y < h; y++)
  {
    const uint8_t f = 0;
    g_byte_array_append(raw, &f, 1);
    for(int x = 0; x < w; x++)
    {
      const int i = y * w + x;
      const int r = i % P6B_N, g = (i / P6B_N) % P6B_N, b = i / (P6B_N * P6B_N);
      double o[3];
      p6b_warm(r / 3.0, g / 3.0, b / 3.0, o);
      const uint8_t px[3] = { lround(o[0] * 255), lround(o[1] * 255), lround(o[2] * 255) };
      g_byte_array_append(raw, px, 3);
    }
  }
  uint32_t a = 1, bsum = 0;  // adler32
  for(guint i = 0; i < raw->len; i++)
  {
    a = (a + raw->data[i]) % 65521;
    bsum = (bsum + a) % 65521;
  }
  GByteArray *z = g_byte_array_new();
  const uint8_t zh[2] = { 0x78, 0x01 };
  g_byte_array_append(z, zh, 2);
  const uint16_t len = raw->len;
  const uint8_t blk[5] = { 1, len & 0xff, len >> 8, (~len) & 0xff, ((uint16_t)~len) >> 8 };
  g_byte_array_append(z, blk, 5);
  g_byte_array_append(z, raw->data, raw->len);
  p6b_be32(z, (bsum << 16) | a);

  GByteArray *png = g_byte_array_new();
  const uint8_t sig[8] = { 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };
  g_byte_array_append(png, sig, 8);
  GByteArray *ih = g_byte_array_new();
  p6b_be32(ih, w);
  p6b_be32(ih, h);
  const uint8_t rest[5] = { 8, 2, 0, 0, 0 };  // 8 bit, RGB
  g_byte_array_append(ih, rest, 5);
  p6b_chunk(png, "IHDR", ih->data, ih->len);
  p6b_chunk(png, "IDAT", z->data, z->len);
  p6b_chunk(png, "IEND", NULL, 0);
  gchar *dir = g_path_get_dirname(path);
  g_mkdir_with_parents(dir, 0755);
  g_free(dir);
  const int bad = !g_file_set_contents(path, (const gchar *)png->data, png->len, NULL);
  g_byte_array_free(ih, TRUE);
  g_byte_array_free(png, TRUE);
  g_byte_array_free(z, TRUE);
  g_byte_array_free(raw, TRUE);
  return bad;
}

static double p6b_maxdiff(const uint8_t *a, const uint8_t *b, size_t n)
{
  int m = 0;
  for(size_t i = 0; i < n; i++)
    if((i & 3) != 3) m = MAX(m, abs((int)a[i] - (int)b[i]));
  return m;
}

static uint8_t *p6b_render_to(const char *outdir, const char *name, int *w, int *h)
{
  uint8_t *buf = render_full(w, h, NULL);
  if(name)
  {
    gchar *f = g_strdup_printf("%s_bridge.ppm", name);
    write_ppm(path_join(outdir, f), buf, *w, *h);
    g_free(f);
    f = g_strdup_printf("%s.xmp", name);
    MUST(dtb_xmp_write(path_join(outdir, f)));
    g_free(f);
  }
  return buf;
}

static int cmd_lut3dfiles(const char *raw, const char *xmp, const char *outdir)
{
  char *root = path_join(outdir, "p6b_luts");
  char *cube = p6b_cube(0), *ident = p6b_cube(1), *tdl = p6b_3dl();
  CHECK(!p6b_write(path_join(root, "cube/warm.cube"), cube) && !p6b_write(path_join(root, "cube/identity.cube"), ident)
        && !p6b_write(path_join(root, "3dl/warm.3dl"), tdl) && !p6b_png(path_join(root, "hald/warm.png"))
        && !p6b_write(path_join(root, "Warm.Cube"), cube) && !p6b_write(path_join(root, "pack.gmz"), "x"),
        "cannot write the LUT files");

  // names lut3d reads (case by case, as _calculate_clut)
  CHECK(dtb_lut_name_usable("a/b.cube") && dtb_lut_name_usable("B.CUBE") && dtb_lut_name_usable("x.3DL")
        && dtb_lut_name_usable("h.png") && !dtb_lut_name_usable("Warm.Cube") && !dtb_lut_name_usable("p.gmz")
        && !dtb_lut_name_usable("x.txt"), "dtb_lut_name_usable");

  CHECK(dtb_lut_folder_set("relative/luts") == DTB_E_ARG, "a relative LUT folder was taken");
  MUST(dtb_lut_folder_set(root));
  MUST(dtb_open(raw, xmp));
  int w = 0, h = 0;
  uint8_t *off = p6b_render_to(outdir, NULL, &w, &h);

  action("lut3d", 0, "set_file", "{\"path\": \"../x.cube\"}", DTB_E_ARG);
  action("lut3d", 0, "set_file", "{\"path\": \"cube/../../x.cube\"}", DTB_E_ARG);
  action("lut3d", 0, "set_file", "{\"path\": \"/etc/passwd\"}", DTB_E_ARG);
  action("lut3d", 0, "set_file", "{\"path\": \"pack.gmz\"}", DTB_E_ARG);
  action("lut3d", 0, "set_file", "{\"path\": \"Warm.Cube\"}", DTB_E_ARG);
  action("lut3d", 0, "set_file", "{\"path\": \"cube/none.cube\"}", DTB_E_IO);

  static const struct { const char *rel, *name; } files[] = {
    { "cube/warm.cube", "p6b_cube" }, { "3dl/warm.3dl", "p6b_3dl" }, { "hald/warm.png", "p6b_png" } };
  uint8_t *warm = NULL;
  for(size_t i = 0; i < G_N_ELEMENTS(files); i++)
  {
    char args[256];
    snprintf(args, sizeof(args), "{\"path\": \"%s\"}", files[i].rel);
    action("lut3d", 0, "set_file", args, DTB_OK);
    MUST(dtb_history_seal());
    JsonNode *mods = get_modules();
    JsonObject *lut = find_mod(mods, "lut3d", 0);
    const char *fp = lut ? json_node_get_string(param_node(lut, "filepath")) : "";
    CHECK(lut && !strcmp(fp, files[i].rel) && json_object_get_boolean_member(lut, "enabled"),
          "%s: filepath '%s'", files[i].rel, fp);
    json_node_unref(mods);
    int w2, h2;
    uint8_t *buf = p6b_render_to(outdir, files[i].name, &w2, &h2);
    const double d = p6b_maxdiff(buf, off, (size_t)w * h * 4);
    fprintf(stdout, "RESULT %s differs from no LUT by %g (8-bit)\n", files[i].rel, d);
    CHECK(w2 == w && h2 == h && d >= 8, "%s: the LUT did not change the picture", files[i].rel);
    if(i == 0) warm = buf;
    else free(buf);
  }
  // an absolute path inside the folder is stored relative to it, as desktop's chooser does
  {
    char args[1024];
    snprintf(args, sizeof(args), "{\"path\": \"%s/cube/identity.cube\"}", root);
    action("lut3d", 0, "set_file", args, DTB_OK);
    JsonNode *mods = get_modules();
    CHECK(!strcmp(json_node_get_string(param_node(find_mod(mods, "lut3d", 0), "filepath")), "cube/identity.cube"),
          "absolute path inside the folder not made relative");
    json_node_unref(mods);
  }

  // missing file: copy warm.cube to gone.cube, choose it, remove it, set the folder again
  CHECK(!p6b_write(path_join(root, "cube/gone.cube"), cube), "cannot write gone.cube");
  action("lut3d", 0, "set_file", "{\"path\": \"cube/gone.cube\"}", DTB_OK);
  MUST(dtb_history_seal());
  int w3, h3;
  uint8_t *gone = p6b_render_to(outdir, NULL, &w3, &h3);
  CHECK(p6b_maxdiff(gone, warm, (size_t)w * h * 4) == 0, "gone.cube (a copy of warm.cube) renders unlike warm.cube");
  free(gone);
  g_unlink(path_join(root, "cube/gone.cube"));
  MUST(dtb_lut_folder_set(root));
  uint8_t *missing = p6b_render_to(outdir, "p6b_missing", &w3, &h3);
  // lut3d switched on without a table copies its input (process(), "no clut"); that differs from the module
  // switched off by at most 1 in 8 bits here (darktable-cli renders p6b_missing.xmp the same, below in run_tests.sh)
  const double dm = p6b_maxdiff(missing, off, (size_t)w * h * 4);
  fprintf(stdout, "RESULT missing file: differs from the module switched off by %g (8-bit)\n", dm);
  CHECK(dm <= 1, "a missing file does not render as a pass-through (%g)", dm);
  JsonNode *mods = get_modules();
  CHECK(!strcmp(json_node_get_string(param_node(find_mod(mods, "lut3d", 0), "filepath")), "cube/gone.cube"),
        "a missing file's path was not kept");
  json_node_unref(mods);
  // the file comes back (as "Get from LightPhotoLibrary" brings it): read again after the folder is set again
  CHECK(!p6b_write(path_join(root, "cube/gone.cube"), cube), "cannot write gone.cube");
  uint8_t *still = p6b_render_to(outdir, NULL, &w3, &h3);
  fprintf(stdout, "RESULT file back, folder not set again: differs from no LUT by %g\n",
          p6b_maxdiff(still, off, (size_t)w * h * 4));
  free(still);
  MUST(dtb_lut_folder_set(root));
  uint8_t *back = p6b_render_to(outdir, NULL, &w3, &h3);
  const double db = p6b_maxdiff(back, warm, (size_t)w * h * 4);
  fprintf(stdout, "RESULT file back, folder set again: differs from warm.cube by %g\n", db);
  CHECK(db == 0, "the file that came back is not read again (%g)", db);
  free(back);
  g_unlink(path_join(root, "cube/gone.cube"));  // darktable-cli renders p6b_missing.xmp without it

  // no folder: lut3d is a pass-through, as desktop without the preference
  MUST(dtb_lut_folder_set(NULL));
  uint8_t *nofolder = p6b_render_to(outdir, NULL, &w3, &h3);
  const double dn = p6b_maxdiff(nofolder, missing, (size_t)w * h * 4);
  fprintf(stdout, "RESULT no LUT folder: differs from the missing file's render by %g\n", dn);
  CHECK(dn == 0, "no LUT folder: not the same pass-through as a missing file (%g)", dn);
  free(missing);
  free(nofolder);
  action("lut3d", 0, "set_file", "{\"path\": \"cube/warm.cube\"}", DTB_E_ARG);
  MUST(dtb_lut_folder_set(root));

  free(warm);
  free(off);
  g_free(cube);
  g_free(ident);
  g_free(tdl);
  MUST(dtb_close());
  return g_fail;
}
