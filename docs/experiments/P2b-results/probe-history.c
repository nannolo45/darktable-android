// SPDX-License-Identifier: GPL-3.0-or-later
// P2b: minimal reproductions of the contract differences, on the Linux bridge
#include "dtbridge.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static void hist(const char *what){char *j=NULL;if(dtb_history(&j)){printf("%s: history failed %s\n",what,dtb_last_error());return;}
  // print end and count of items and the last 3 ops
  int n=0; const char *p=j; while((p=strstr(p,"\"num\""))){n++;p++;}
  char *e=strstr(j,"\"end\":"); printf("%-40s end=%d items=%d  tail=%s\n",what,atoi(e+6),n, strlen(j)>260? j+strlen(j)-260 : j); dtb_free(j);}
#define C(x) do{int r=(x); if(r) printf("  %s -> %d %s\n", #x, r, dtb_last_error());}while(0)
int main(int argc,char**argv){
  const char *raw=argv[1], *xmp=(argc>2 && argv[2][0])?argv[2]:NULL, *w=getenv("W");
  char c[512],ca[512],t[512],l[512]; snprintf(c,512,"%s/config",w);snprintf(ca,512,"%s/cache",w);snprintf(t,512,"%s/tmp",w);snprintf(l,512,":memory:");
  dtb_paths_t p={c,ca,"/home/claude/dt/install/share/darktable","/home/claude/dt/install/lib/darktable",t,l};
  C(dtb_init(&p,2));
  C(dtb_open(raw,xmp)); hist("open");
  C(dtb_set_enabled("colorbalancergb",0,0)); hist("set_enabled cb off");
  C(dtb_param_set("colorbalancergb",0,"vibrance","0.1")); hist("param_set vibrance .1");
  C(dtb_param_set("colorbalancergb",0,"vibrance","0.2")); hist("param_set vibrance .2");
  C(dtb_history_seal());
  C(dtb_set_enabled("exposure",0,0)); hist("set_enabled exposure off");
  C(dtb_params_set("sigmoid",0,"{\"middle_grey_contrast\":2.0,\"contrast_skewness\":0.5}")); hist("params_set sigmoid");
  C(dtb_history_goto(1000));
  C(dtb_close());
  if(argc>3){ C(dtb_open(raw,argv[3])); hist("open guard xmp"); char *j=NULL; dtb_history(&j); printf("%s\n",j); dtb_free(j);
    char *e=NULL; dtb_image_info(&e); printf("%.200s\n", e); dtb_free(e);
    C(dtb_history_goto(0)); hist("goto 0"); C(dtb_close()); }
  dtb_shutdown(); return 0;}
