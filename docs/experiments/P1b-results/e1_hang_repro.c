// SPDX-License-Identifier: GPL-3.0-or-later
// P1b E1: the P2f hang on the bridge before the fix: open a junk file, render the preview
#include "dtbridge.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
int main(int argc, char **argv)
{
  const char *pre = getenv("DT_PREFIX"), *w = argv[1], *file = argv[2];
  char a[512], b[512], c[512], d[512], e[512], l[512];
  snprintf(a, sizeof a, "%s/config", w); snprintf(b, sizeof b, "%s/cache", w);
  snprintf(c, sizeof c, "%s/share/darktable", pre); snprintf(d, sizeof d, "%s/lib/darktable", pre);
  snprintf(e, sizeof e, "%s/tmp", w); snprintf(l, sizeof l, ":memory:");
  mkdir(w, 0700);
  dtb_paths_t p = { a, b, c, d, e, l };
  if(dtb_init(&p, 0)) return 1;
  int r = dtb_open(file, NULL);
  printf("open %d %s\n", r, dtb_last_error()); fflush(stdout);
  if(r) return 0;
  char *j = NULL;
  if(getenv("INFO")) r = dtb_image_info(&j); else r = -100;
  printf("image_info %d %s\n", r, j ? j : dtb_last_error()); fflush(stdout);
  unsigned char *buf = NULL; int ww = 0, hh = 0;
  for(int k = 0; k < 3; k++)
  {
    r = dtb_render_preview(512, &buf, &ww, &hh);
    printf("render_preview %d: %d %dx%d %s\n", k, r, ww, hh, r ? dtb_last_error() : ""); fflush(stdout);
    if(!r) { dtb_free(buf); buf = NULL; }
  }
  dtb_close(); dtb_shutdown();
  return 0;
}
