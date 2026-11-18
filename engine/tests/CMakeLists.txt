# SPDX-License-Identifier: GPL-3.0-or-later
# test driver: uses dtbridge.h only, never darktable's headers
add_executable(dtb_test dtb_test.c)
target_link_libraries(dtb_test PRIVATE dtbridge m pthread)
set_target_properties(dtb_test PROPERTIES BUILD_RPATH "$<TARGET_FILE_DIR:dtbridge>;${DARKTABLE_LIBDIR}")
