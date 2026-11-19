// SPDX-License-Identifier: GPL-3.0-or-later
// P2b: reopening an image without an XMP in the same process
#include "dtbridge.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static void hist(const char *what){char *j=NULL;dtb_history(&j);int n=0;const char*p=j;while((p=strstr(p,"\"num\""))){n++;p++;}
  printf("%-34s end=%d items=%d\n",what,atoi(strstr(j,"\"end\":")+6),n);dtb_free(j);}
int main(int argc,char**argv){const char*w=getenv("W");char c[512],ca[512],t[512];
  snprintf(c,512,"%s/config",w);snprintf(ca,512,"%s/cache",w);snprintf(t,512,"%s/tmp",w);
  dtb_paths_t p={c,ca,"/home/claude/dt/install/share/darktable","/home/claude/dt/install/lib/darktable",t,":memory:"};
  dtb_init(&p,2);
  dtb_open(argv[1],NULL); hist("first open, no XMP");
  dtb_param_set("exposure",0,"exposure","1.5"); dtb_history_seal();
  dtb_param_set("colorbalancergb",0,"vibrance","0.3"); dtb_history_seal(); hist("two changes");
  dtb_history_goto(11); hist("goto 11");
  dtb_close(); dtb_open(argv[1],NULL); hist("second open, no XMP, same process");
  dtb_close(); dtb_shutdown(); return 0;}
