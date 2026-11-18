// SPDX-License-Identifier: GPL-3.0-or-later
//
// Force-included (-include) into every darktable compilation unit when
// building for Android (tools/build-engine-android.sh). It supplies what
// bionic lacks so that darktable's source stays unmodified.
//
// pthread_setcancelstate(): bionic has no thread cancellation at all
// (no pthread_cancel), so there is nothing to enable or disable. darktable
// calls it only around a condition wait in src/control/jobs.c. A no-op that
// reports PTHREAD_CANCEL_ENABLE as the previous state is exact.
// Termux patches jobs.c instead (x11-packages/darktable/0002-jobs-pthread.patch).
#pragma once
#if defined(__ANDROID__)
#include <pthread.h>
#ifndef PTHREAD_CANCEL_ENABLE
#define PTHREAD_CANCEL_ENABLE 0
#define PTHREAD_CANCEL_DISABLE 1
static inline int dt_android_pthread_setcancelstate(int state, int *oldstate)
{
  (void)state;
  if(oldstate) *oldstate = PTHREAD_CANCEL_ENABLE;
  return 0;
}
#define pthread_setcancelstate dt_android_pthread_setcancelstate
#endif
#endif
