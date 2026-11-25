# SPDX-License-Identifier: GPL-3.0-or-later
#
# Engine bridge (briefs S3, G0, P1a). Builds libdtbridge out of tree against an
# installed darktable and the upstream source it was built from. The engine
# build scripts (tools/build-engine-android.sh, tools/build-engine-linux.sh)
# run it right after darktable, on darktable's own build tree, with the same
# toolchain and flags; for Android pass the same CMAKE_TOOLCHAIN_FILE and
# ANDROID_* settings as darktable's configure.
#
#   cmake -S engine -B <build> -G Ninja \
#     -DDT_SOURCE_DIR=<darktable source> \
#     -DDT_BUILD_DIR=<darktable build dir, Ninja generator> \
#     -DDT_INSTALL_PREFIX=<darktable install prefix>
#
# darktable installs no headers, and the layout of its structs depends on
# compile definitions (HAVE_OPENCL, USE_LUA, HAVE_GPHOTO2, ...). The bridge must
# therefore be compiled with exactly the definitions and include paths used for
# libdarktable. They are read from darktable's own build (ninja -t commands).
cmake_minimum_required(VERSION 3.18)
project(dtbridge C)

set(DT_SOURCE_DIR "" CACHE PATH "darktable source tree (upstream/darktable)")
set(DT_BUILD_DIR "" CACHE PATH "darktable build tree (Ninja)")
set(DT_INSTALL_PREFIX "" CACHE PATH "darktable install prefix")
foreach(v DT_SOURCE_DIR DT_BUILD_DIR DT_INSTALL_PREFIX)
  if(NOT ${v} OR NOT EXISTS "${${v}}")
    message(FATAL_ERROR "${v} must point to an existing directory")
  endif()
endforeach()

find_program(NINJA_EXE ninja REQUIRED)
execute_process(
  COMMAND ${NINJA_EXE} -C ${DT_BUILD_DIR} -t commands lib_darktable
  OUTPUT_VARIABLE _dt_cmds RESULT_VARIABLE _dt_res ERROR_QUIET)
if(NOT _dt_res EQUAL 0)
  message(FATAL_ERROR "cannot read build commands from ${DT_BUILD_DIR} (Ninja generator required)")
endif()
string(REGEX MATCH "[^\n]*-c [^\n]*/src/common/darktable\\.c[^\n]*" _dt_line "${_dt_cmds}")
if(NOT _dt_line)
  message(FATAL_ERROR "compile command for common/darktable.c not found")
endif()
separate_arguments(_dt_args UNIX_COMMAND "${_dt_line}")

set(DT_DEFS "")
set(DT_INCS "")
set(DT_SYS_INCS "")
set(DT_OPTS "")
set(_next_sys FALSE)
foreach(a ${_dt_args})
  if(_next_sys)
    list(APPEND DT_SYS_INCS "${a}")
    set(_next_sys FALSE)
  elseif(a STREQUAL "-isystem")
    set(_next_sys TRUE)
  elseif(a MATCHES "^-D(.*)")
    set(_def "${CMAKE_MATCH_1}")
    if(NOT _def MATCHES "^lib_darktable_EXPORTS")
      list(APPEND DT_DEFS "${_def}")
    endif()
  elseif(a MATCHES "^-I(.*)")
    list(APPEND DT_INCS "${CMAKE_MATCH_1}")
  elseif(a MATCHES "^-(std=|march=|fopenmp|ffast-math|fno-finite-math-only)")
    list(APPEND DT_OPTS "${a}")
  endif()
endforeach()
message(STATUS "darktable definitions: ${DT_DEFS}")

find_package(PkgConfig REQUIRED)
# lcms2 and lensfun: the white balance and lens actions (src/actions.c) call
# them directly, as the modules do; sqlite3: the session reads the image's
# history rows before darktable rewrites them (src/session.c, P1c). All three
# are darktable dependencies already
pkg_check_modules(DTB_DEPS REQUIRED IMPORTED_TARGET glib-2.0 gobject-2.0 json-glib-1.0 libxml-2.0
                  lcms2 lensfun sqlite3)
find_package(OpenMP REQUIRED COMPONENTS C)

# NO_CMAKE_FIND_ROOT_PATH: a cross toolchain (Android NDK) would otherwise
# re-root this absolute path under its sysroot
find_library(DARKTABLE_LIB darktable PATHS "${DT_INSTALL_PREFIX}/lib/darktable"
             "${DT_INSTALL_PREFIX}/lib64/darktable" NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH REQUIRED)
get_filename_component(DARKTABLE_LIBDIR "${DARKTABLE_LIB}" DIRECTORY)

add_library(dtbridge SHARED
  src/core.c        # errors, lifecycle, engine information, threads (2.1)
  src/guard.c       # version guard (2.2)
  src/session.c     # open, close, image information, metadata (2.3)
  src/params.c      # modules and parameters (2.4)
  src/actions.c     # module actions (2.4)
  src/actions2.c    # module actions added by P1b (5.4)
  src/instances.c   # instances (2.5), prototype mask
  src/history.c     # history, grouping, undo and redo (2.6)
  src/render.c      # rendering (2.7)
  src/output.c      # XMP and export (2.8)
  src/presets.c     # presets (P1b, 5.2)
  src/styles.c      # styles (P1b, 5.3)
  src/shared.c      # styles and presets shared through LightPhotoLibrary (P3l, PLAN.md 4.5)
  src/copypaste.c   # copy and paste of the history (P3j, 9)
  src/times.c       # per-module times of the last render (P1b, 5.6)
  src/maskdisplay.c # mask display for one render (P1b part 2, 6.8)
  src/pickers.c     # colour pickers, histogram (P1b part 2, 6.3, 6.4)
  src/actions3.c    # module actions that pick (P1b part 2, 6.3)
  src/coords.c      # coordinates original <-> processed (P1b part 2, 6.5)
  src/blend.c       # blending of an instance (P1b part 2, 6.6)
  src/masks.c       # drawn masks (P1b part 2, 6.7)
  src/retouch.c     # retouch shapes (P3m, 6.10)
  src/perspective.c # rotate and perspective: structure, guides, fit (P3n, 6.11)
  src/memory.c      # memory held and given back (P4a, 10)
  src/speed.c       # resources, OpenCL opt-in (P4b, 11)
)
# P3n: perspective.c compiles darktable's own iop/ashift.c (unmodified) to call
# its structure, fit and mouse functions; every symbol of it stays inside the
# library (the copy of the module's entry points never meets the module
# darktable loads), and it links GTK as the module does (darktable's own
# dependency; GTK is never initialised)
set_source_files_properties(src/perspective.c PROPERTIES COMPILE_OPTIONS "-fvisibility=hidden")
pkg_check_modules(DTB_GTK REQUIRED IMPORTED_TARGET gtk+-3.0)
target_include_directories(dtbridge PUBLIC include)
target_include_directories(dtbridge PRIVATE src ${DT_INCS})
target_include_directories(dtbridge SYSTEM PRIVATE ${DT_SYS_INCS})
target_compile_definitions(dtbridge PRIVATE ${DT_DEFS})
target_compile_options(dtbridge PRIVATE ${DT_OPTS} -Wall -Wno-unknown-pragmas)
target_link_libraries(dtbridge PRIVATE ${DARKTABLE_LIB} PkgConfig::DTB_DEPS PkgConfig::DTB_GTK OpenMP::OpenMP_C m)
set_target_properties(dtbridge PROPERTIES BUILD_RPATH "${DARKTABLE_LIBDIR}")

# the test driver dtb_test (S3 tests); runs on Linux only
option(DTB_BUILD_TESTS "build the bridge test driver" ON)
if(DTB_BUILD_TESTS)
  enable_testing()
  add_subdirectory(tests)
endif()
