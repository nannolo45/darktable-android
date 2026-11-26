// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Brief P6d: the focal length, aperture and distance lists of iop/lens.cc _lens_set(), transcribed as it is (the
 * arrays, the index arithmetic, _precision() and the "%.*f" text), for the lenses the JVM test
 * (app/darkroom .../P6dLensListsTest.kt) checks the app's LensValues against. Each lens is given as Lensfun gives it
 * (floats). Prints one line per lens: its three lists as the combobox shows them (the current value first).
 *
 *   gcc -O0 -o /tmp/p6d-ref tools/p6d-lens-lists-reference.c -lm && /tmp/p6d-ref
 *
 * The desktop source is upstream/darktable/src/iop/lens.cc (pinned commit), function _lens_set, from the line
 * "gdouble focal_values[]" to the end of the distance loop.
 */
#include <math.h>
#include <stdio.h>
#include <string.h>

#define M_SQRT2_F 1.41421356f

static int _precision(double x, double adj)
{
  x *= adj;
  if(x == 0) return 1;
  if(x < 1.0)
    if(x < 0.1)
      if(x < 0.01) return 5;
      else return 4;
    else return 3;
  else if(x < 100.0)
    if(x < 10.0) return 2;
    else return 1;
  else return 0;
}

typedef struct { const char *name; float minf, maxf, mina, maxa; float focal, aperture, distance; } lens_t;

static void show(const lens_t *lens)
{
  double focal_values[]
      = { -INFINITY, 4.5,   8,   10,  12,  14,  15,  16,  17,  18,  20,  24,  28,   30,
                 31,  35,  38,   40,  43,  45,  50,  55,  60,  70,  75,  77,  80,   85,
                 90, 100, 105,  110, 120, 135, 150, 200, 210, 240, 250, 300, 400,  500,
		 600, 700, 800, 840, 1000, 1120, 1200, 1600, 2000, INFINITY };
  double aperture_values[]
      = { -INFINITY, 0.7, 0.8, 0.9,   1, 1.1, 1.2, 1.4, 1.8,  2,  2.2, 2.5, 2.8, 3.2, 3.4,
                  4, 4.5, 5.0, 5.6, 6.3, 7.1,   8,   9,  10, 11,   13,  14,  16,  18,  20,
		  22,  25,  29,  32,  38, 45, 50, 54, 64, 90, INFINITY };
  unsigned i;
  char txt[30];
  int ffi = 1, fli = -1;
  for(i = 1; i < sizeof(focal_values) / sizeof(double) - 1; i++)
  {
    if(focal_values[i] < lens->minf) ffi = i + 1;
    if(focal_values[i] > lens->maxf && fli == -1) fli = i;
  }
  if(focal_values[ffi] > lens->minf) { focal_values[ffi - 1] = lens->minf; ffi--; }
  if(lens->maxf == 0 || fli < 0) fli = sizeof(focal_values) / sizeof(double) - 2;
  if(focal_values[fli + 1] < lens->maxf) { focal_values[fli + 1] = lens->maxf; ffi++; }
  if(fli < ffi) fli = ffi + 1;

  printf("%s\n  mm:", lens->name);
  snprintf(txt, sizeof(txt), "%.*f", _precision(lens->focal, 10.0), lens->focal);
  printf(" [%s]", txt);
  for(int k = 0; k < fli - ffi; k++)
  {
    snprintf(txt, sizeof(txt), "%.*f", _precision(focal_values[ffi + k], 10.0), focal_values[ffi + k]);
    printf(" %s", txt);
  }
  ffi = 1, fli = sizeof(aperture_values) / sizeof(double) - 1;
  for(i = 1; i < sizeof(aperture_values) / sizeof(double) - 1; i++)
    if(aperture_values[i] < lens->mina) ffi = i + 1;
  if(aperture_values[ffi] > lens->mina) { aperture_values[ffi - 1] = lens->mina; ffi--; }
  printf("\n  f/:");
  snprintf(txt, sizeof(txt), "%.*f", _precision(lens->aperture, 10.0), lens->aperture);
  printf(" [%s]", txt);
  for(int k = 0; k < fli - ffi; k++)
  {
    snprintf(txt, sizeof(txt), "%.*f", _precision(aperture_values[ffi + k], 10.0), aperture_values[ffi + k]);
    printf(" %s", txt);
  }
  printf("\n  d:");
  snprintf(txt, sizeof(txt), "%.*f", _precision(lens->distance, 10.0), lens->distance);
  printf(" [%s]", txt);
  float val = 0.25f;
  for(int k = 0; k < 25; k++)
  {
    if(val > 1000.0f) val = 1000.0f;
    snprintf(txt, sizeof(txt), "%.*f", _precision(val, 10.0), val);
    printf(" %s", txt);
    if(val >= 1000.0f) break;
    val *= M_SQRT2_F;
  }
  printf("\n");
}

int main(void)
{
  const lens_t lenses[] = {
    { "zoom 18-55 f/3.5-5.6 (focal 35, f/5.6, 2.5 m)", 18.f, 55.f, 3.5f, 5.6f, 35.f, 5.6f, 2.5f },
    { "zoom 17-70 f/2.8 (focal 17.5 not in the table)", 17.f, 70.f, 2.8f, 22.f, 17.5f, 2.8f, 1000.f },
    { "zoom 10-22 f/3.5-4.5", 10.f, 22.f, 3.5f, 22.f, 12.f, 4.f, 1000.f },
    { "zoom 70-300 f/4-5.6 (max 300 is a table value)", 70.f, 300.f, 4.f, 32.f, 200.f, 5.6f, 1000.f },
    { "zoom 52-65 (neither end in the table)", 52.f, 65.f, 2.f, 16.f, 60.f, 4.f, 1000.f },
    { "prime 100 f/2.8", 100.f, 100.f, 2.8f, 32.f, 100.f, 2.8f, 1.f },
    { "prime 4.5 fisheye f/2.8", 4.5f, 4.5f, 2.8f, 22.f, 4.5f, 8.f, 1000.f },
    { "prime 85 f/1.4", 85.f, 85.f, 1.4f, 16.f, 85.f, 1.4f, 3.f },
    { "prime 52 (not in the table) f/1.8", 52.f, 52.f, 1.8f, 16.f, 52.f, 2.f, 1000.f },
    { "no f-number known (0)", 24.f, 70.f, 0.f, 0.f, 50.f, 4.f, 1000.f },
  };
  for(size_t i = 0; i < sizeof(lenses) / sizeof(lenses[0]); i++) show(&lenses[i]);
  return 0;
}
