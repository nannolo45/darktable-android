# SPDX-License-Identifier: GPL-3.0-or-later
# test driver: uses dtbridge.h and json-glib, never darktable's headers
add_executable(dtb_test dtb_test.c)
target_link_libraries(dtb_test PRIVATE dtbridge PkgConfig::DTB_DEPS m pthread)
set_target_properties(dtb_test PROPERTIES BUILD_RPATH "$<TARGET_FILE_DIR:dtbridge>;${DARKTABLE_LIBDIR}")

# brief P3c deliverable 6 (test C25): darktable's own picker callbacks, copied verbatim out of the darktable
# source this bridge is built against (pickref_extract.py, at build time), compiled with darktable's definitions
# and run against the bridge's picker actions by dtb_pickref. Unlike dtb_test it includes darktable's headers and
# the bridge's internal header (engine/src/dtb_internal.h): it needs a module's own structure and the bridge's pipe.
find_package(Python3 REQUIRED COMPONENTS Interpreter)
# the copied callbacks are the modules' own code: they refer to GTK as the modules do
pkg_check_modules(PICKREF_GTK REQUIRED IMPORTED_TARGET gtk+-3.0)
set(PICKREF_OPS temperature exposure rgblevels rgbcurve channelmixerrgb
    levels invert splittoning colorize profile_gamma basicadj colorequal colorzones agx)  # brief P6a: test C43
set(PICKREF_GEN "${CMAKE_CURRENT_BINARY_DIR}/pickref-gen")
set(PICKREF_INC "")
set(PICKREF_UPSTREAM "")
set(PICKREF_SRC "")
foreach(op ${PICKREF_OPS})
  list(APPEND PICKREF_INC "${PICKREF_GEN}/pickref_up_${op}_types.inc" "${PICKREF_GEN}/pickref_up_${op}_funcs.inc")
  list(APPEND PICKREF_UPSTREAM "${DT_SOURCE_DIR}/src/iop/${op}.c")
  list(APPEND PICKREF_SRC "pickref_${op}.c")
endforeach()
add_custom_command(
  OUTPUT ${PICKREF_INC}
  COMMAND ${Python3_EXECUTABLE} -I "${CMAKE_CURRENT_SOURCE_DIR}/pickref_extract.py" "${DT_SOURCE_DIR}" "${PICKREF_GEN}"
  DEPENDS pickref_extract.py ${PICKREF_UPSTREAM}
  COMMENT "copying darktable's picker callbacks for test C25")
add_executable(dtb_pickref dtb_pickref.c ${PICKREF_SRC} ${PICKREF_INC})
target_include_directories(dtb_pickref PRIVATE "${PICKREF_GEN}" "${CMAKE_CURRENT_SOURCE_DIR}" "${PROJECT_SOURCE_DIR}/src" ${DT_INCS})
target_include_directories(dtb_pickref SYSTEM PRIVATE ${DT_SYS_INCS})
target_compile_definitions(dtb_pickref PRIVATE ${DT_DEFS})
target_compile_options(dtb_pickref PRIVATE ${DT_OPTS} -Wall -Wno-unknown-pragmas)
target_link_libraries(dtb_pickref PRIVATE dtbridge ${DARKTABLE_LIB} PkgConfig::DTB_DEPS PkgConfig::PICKREF_GTK OpenMP::OpenMP_C m)
set_target_properties(dtb_pickref PROPERTIES BUILD_RPATH "$<TARGET_FILE_DIR:dtbridge>;${DARKTABLE_LIBDIR}")

# brief P3j (C29): desktop's own copy and paste on plain library images, the reference for the bridge's
add_executable(dtb_p3jref dtb_p3jref.c)
target_include_directories(dtb_p3jref PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${PROJECT_SOURCE_DIR}/src" ${DT_INCS})
target_include_directories(dtb_p3jref SYSTEM PRIVATE ${DT_SYS_INCS})
target_compile_definitions(dtb_p3jref PRIVATE ${DT_DEFS})
target_compile_options(dtb_p3jref PRIVATE ${DT_OPTS} -Wall -Wno-unknown-pragmas)
target_link_libraries(dtb_p3jref PRIVATE dtbridge ${DARKTABLE_LIB} PkgConfig::DTB_DEPS PkgConfig::PICKREF_GTK OpenMP::OpenMP_C m)
set_target_properties(dtb_p3jref PROPERTIES BUILD_RPATH "$<TARGET_FILE_DIR:dtbridge>;${DARKTABLE_LIBDIR}")
