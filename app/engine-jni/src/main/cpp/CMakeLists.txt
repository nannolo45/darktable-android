# SPDX-License-Identifier: GPL-3.0-or-later
# JNI layer over dtbridge.h (brief G0). Links against libdtbridge.so from the
# engine archive of the same ABI (tools/build-engine-android.sh), but does not
# package it: the app unpacks the whole engine tree, the bridge included, into
# its own storage and loads it from there before this library.
cmake_minimum_required(VERSION 3.18)
project(dtjni C)

if(NOT DTA_ENGINE_DIR OR NOT EXISTS "${DTA_ENGINE_DIR}/engine-${ANDROID_ABI}/lib/libdtbridge.so")
  message(FATAL_ERROR "DTA_ENGINE_DIR must hold engine-${ANDROID_ABI}/lib/libdtbridge.so "
                      "(gradle property dta.engineDir; got '${DTA_ENGINE_DIR}')")
endif()
if(NOT DTB_INCLUDE_DIR OR NOT EXISTS "${DTB_INCLUDE_DIR}/dtbridge.h")
  message(FATAL_ERROR "DTB_INCLUDE_DIR must point to engine/include")
endif()

add_library(dtjni SHARED dtjni.c)
target_include_directories(dtjni PRIVATE "${DTB_INCLUDE_DIR}")
target_compile_options(dtjni PRIVATE -Wall -Wextra -Wno-unused-parameter)
# by path, not as an IMPORTED target, so that the build does not copy the
# bridge into the APK's lib/ folder as well
target_link_libraries(dtjni PRIVATE "${DTA_ENGINE_DIR}/engine-${ANDROID_ABI}/lib/libdtbridge.so" log dl)
target_link_options(dtjni PRIVATE "-Wl,-z,max-page-size=16384")
