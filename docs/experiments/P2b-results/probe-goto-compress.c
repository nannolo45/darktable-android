// SPDX-License-Identifier: GPL-3.0-or-later
// P2b: history_goto equality and compress of a module that ends up off (contract tests)
#include "dtbridge.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static void save(const char *f, int (*g)(char**)){char*j=NULL;g(&j);FILE*o=fopen(f,"w");fputs(j,o);fclose(o);dtb_free(j);}
int main(int argc,char**argv){const char*w=getenv("W");char c[512],ca[512],t[512];
  snprintf(c,512,"%s/config",w);snprintf(ca,512,"%s/cache",w);snprintf(t,512,"%s/tmp",w);
  dtb_paths_t p={c,ca,"/home/claude/dt/install/share/darktable","/home/claude/dt/install/lib/darktable",t,":memory:"};
  dtb_init(&p,2);
  dtb_open(argv[1],NULL);
  char*j=NULL; dtb_history(&j); int start=atoi(strstr(j,"\"end\":")+6); dtb_free(j);
  dtb_param_set("exposure",0,"exposure","1.0"); dtb_history_seal(); save("mods-at1.json",dtb_modules);
  dtb_param_set("exposure",0,"exposure","2.0"); dtb_history_seal();
  dtb_param_set("colorbalancergb",0,"vibrance","0.5"); dtb_history_seal();
  int r=dtb_history_goto(start+1); printf("goto %d -> %d\n",start+1,r); save("mods-goto.json",dtb_modules);
  dtb_close();
  dtb_open(argv[2],NULL);  // a second copy: compress
  for(int i=0;i<3;i++){char v[16];snprintf(v,16,"%.1f",0.5+0.5*i);dtb_param_set("exposure",0,"exposure",v);dtb_history_seal();}
  dtb_param_set("colorbalancergb",0,"vibrance","0.2");dtb_history_seal();
  dtb_set_enabled("colorbalancergb",0,0);
  save("hist-before-compress.json",dtb_history);
  r=dtb_history_compress(); printf("compress -> %d %s\n",r,r?dtb_last_error():"");
  save("hist-after-compress.json",dtb_history);
  dtb_close(); dtb_shutdown(); return 0;}
