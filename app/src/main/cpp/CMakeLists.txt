# EdgeTX-on-Android — thin native host.
#
# The UI is NOT implemented here: it is the real EdgeTX firmware compiled into
# libedgetx-<flavour>-simulator.so by the EdgeTX CMake project (see the
# app/build.gradle.kts task that builds it). This module only provides the
# NativeActivity glue and drives that library through the platform API
# documented in radio/src/targets/simu/simulib.h.

cmake_minimum_required(VERSION 3.22.1)
project(edgetx_ui C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# android_native_app_glue (NativeActivity main loop) - ships with the NDK.
# ---------------------------------------------------------------------------
get_filename_component(NATIVE_APP_GLUE_DIR
    "${ANDROID_NDK}/sources/android/native_app_glue" ABSOLUTE)

add_library(edgetx_ui SHARED
    native_main.cpp
    simu_host.cpp
    joystick.cpp
    ${NATIVE_APP_GLUE_DIR}/android_native_app_glue.c
)

target_include_directories(edgetx_ui PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${NATIVE_APP_GLUE_DIR}
)

# ---------------------------------------------------------------------------
# SD-card asset manifest.
#
# AAssetDir enumeration turned out to be unreliable for APK assets, so the list
# of bundled SD-card files is generated at configure time and compiled in; the
# runtime copies each entry individually.
# ---------------------------------------------------------------------------
set(SDCARD_ASSET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../assets/sdcard")
if(EXISTS "${SDCARD_ASSET_DIR}")
    file(GLOB_RECURSE SDCARD_FILES CONFIGURE_DEPENDS
        RELATIVE "${SDCARD_ASSET_DIR}" "${SDCARD_ASSET_DIR}/*")
    list(SORT SDCARD_FILES)
endif()

set(SDCARD_ASSET_LIST "")
foreach(_f ${SDCARD_FILES})
    string(REPLACE "\\" "/" _f "${_f}")
    set(SDCARD_ASSET_LIST "${SDCARD_ASSET_LIST}\n    \"${_f}\",")
endforeach()

configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/sdcard_manifest.h.in"
    "${CMAKE_CURRENT_BINARY_DIR}/sdcard_manifest.h"
    @ONLY)
target_include_directories(edgetx_ui PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
message(STATUS "Bundled SD-card assets: ${SDCARD_FILES}")

# ---------------------------------------------------------------------------
# The ported EdgeTX simulator (built by the Gradle task "buildEdgeTxSimulator").
# EDGETX_SIM_DIR contains one sub-directory per ABI.
# ---------------------------------------------------------------------------
# Plain variables (not CACHE): a stale cached value must not survive a move of
# the staging directory.
set(EDGETX_SIM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/edgetx-libs")
set(EDGETX_SIM_NAME "libedgetx-st16mk3-simulator.so")

set(EDGETX_SIM_LIB "${EDGETX_SIM_DIR}/${ANDROID_ABI}/${EDGETX_SIM_NAME}")
if(NOT EXISTS "${EDGETX_SIM_LIB}")
    message(FATAL_ERROR
        "EdgeTX simulator library not found: ${EDGETX_SIM_LIB}\n"
        "Run the buildEdgeTxSimulator Gradle task first (see app/build.gradle.kts).")
endif()

add_library(edgetx_sim SHARED IMPORTED)
set_target_properties(edgetx_sim PROPERTIES IMPORTED_LOCATION "${EDGETX_SIM_LIB}")

# ---------------------------------------------------------------------------
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

target_link_libraries(edgetx_ui PRIVATE
    edgetx_sim
    ${LOG_LIB}
    ${ANDROID_LIB}
)

# Keep the NativeActivity entry point symbol in the .so
target_link_options(edgetx_ui PRIVATE -u ANativeActivity_onCreate)
