# SPDX-License-Identifier: GPL-3.0-or-later
#
# Layout probe (brief P2d): a darktable "lib" plugin, built out of tree against an
# installed darktable and the source and build tree it came from, the same way as
# the schema extractor (schema/extractor/CMakeLists.txt).
#
#   cmake -S tools/layouts/probe -B <build> -G Ninja \
#     -DDT_SOURCE_DIR=<darktable source> \
#     -DDT_BUILD_DIR=<darktable build dir, Ninja generator> \
#     -DDT_INSTALL_PREFIX=<darktable install prefix>
#
# The plugin must be compiled with exactly the definitions, include paths and
# forced includes darktable uses for its own lib plugins, or struct layouts do
# not match. They are read from darktable's build (ninja -t commands), from the
# compile line of an existing lib plugin (src/libs/history.c).
cmake_minimum_required(VERSION 3.18)
project(dt_layout_probe C)

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
  COMMAND ${NINJA_EXE} -C ${DT_BUILD_DIR} -t commands
  OUTPUT_VARIABLE _dt_cmds RESULT_VARIABLE _dt_res ERROR_QUIET)
if(NOT _dt_res EQUAL 0)
  message(FATAL_ERROR "cannot read build commands from ${DT_BUILD_DIR} (Ninja generator required)")
endif()
string(REGEX MATCH "[^\n]*-c [^\n]*/src/libs/history\\.c[^\n]*" _dt_line "${_dt_cmds}")
if(NOT _dt_line)
  message(FATAL_ERROR "compile command for src/libs/history.c not found")
endif()
separate_arguments(_dt_args UNIX_COMMAND "${_dt_line}")

set(DT_DEFS "")
set(DT_INCS "")
set(DT_SYS_INCS "")
set(DT_OPTS "")
set(_next "")
foreach(a ${_dt_args})
  if(_next STREQUAL "isystem")
    list(APPEND DT_SYS_INCS "${a}")
    set(_next "")
  elseif(_next STREQUAL "include")
    list(APPEND DT_OPTS "SHELL:-include ${a}")
    set(_next "")
  elseif(a STREQUAL "-isystem")
    set(_next "isystem")
  elseif(a STREQUAL "-include")
    set(_next "include")
  elseif(a MATCHES "^-D(.*)")
    set(_def "${CMAKE_MATCH_1}")
    if(NOT _def MATCHES "_EXPORTS$")
      list(APPEND DT_DEFS "${_def}")
    endif()
  elseif(a MATCHES "^-I(.*)")
    list(APPEND DT_INCS "${CMAKE_MATCH_1}")
  elseif(a MATCHES "^-(std=|march=|fopenmp|ffast-math|fno-finite-math-only|fvisibility)")
    list(APPEND DT_OPTS "${a}")
  endif()
endforeach()
message(STATUS "darktable definitions: ${DT_DEFS}")

find_package(PkgConfig REQUIRED)
pkg_check_modules(EXT_DEPS REQUIRED IMPORTED_TARGET glib-2.0 gtk+-3.0 sqlite3)
find_package(OpenMP REQUIRED COMPONENTS C)

find_library(DARKTABLE_LIB darktable PATHS "${DT_INSTALL_PREFIX}/lib/darktable"
             "${DT_INSTALL_PREFIX}/lib64/darktable" NO_DEFAULT_PATH REQUIRED)

# the file name must be lib<name>.so for darktable's plugin loader
add_library(layout_probe MODULE layout_probe.c)
set_target_properties(layout_probe PROPERTIES PREFIX "lib" SUFFIX ".so")
target_include_directories(layout_probe PRIVATE ${DT_INCS})
target_include_directories(layout_probe SYSTEM PRIVATE ${DT_SYS_INCS})
target_compile_definitions(layout_probe PRIVATE ${DT_DEFS})
target_compile_options(layout_probe PRIVATE ${DT_OPTS} -Wall -Wno-unknown-pragmas)
target_link_libraries(layout_probe PRIVATE ${DARKTABLE_LIB} PkgConfig::EXT_DEPS OpenMP::OpenMP_C m)
