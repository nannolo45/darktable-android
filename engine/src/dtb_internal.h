// SPDX-License-Identifier: GPL-3.0-or-later
/* shared between the bridge's source files; never included above the bridge */
#ifndef DTB_INTERNAL_H
#define DTB_INTERNAL_H

#include <glib.h>

/* set the message returned by dtb_last_error(); returns code */
int dtb__err(int code, const char *fmt, ...) G_GNUC_PRINTF(2, 3);

/* the version guard. Needs an initialised engine (it compares with the loaded
 * module libraries). Returns a JSON string (g_malloc) or NULL if the file
 * cannot be read; *n_problems = -1 if it cannot be parsed. */
char *dtb__xmp_check(const char *xmp_path, int *n_problems);

#endif
