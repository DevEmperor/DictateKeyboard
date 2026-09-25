# Copyright (C) 2026 DevEmperor (Dictate)
# Licensed under the Apache License, Version 2.0.
#
# libdictate_bergamot.so: Mozilla's Bergamot engine (MPL-2.0) and Marian (MIT), statically linked
# behind our own JNI bridge (issue #424). Configured and built by build-android.sh; not part of the
# Gradle build, so an app build never recompiles Marian.

cmake_minimum_required(VERSION 3.22)
project(dictate_bergamot C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT BERGAMOT_SOURCE_DIR)
  message(FATAL_ERROR "BERGAMOT_SOURCE_DIR must point at mozilla/translations/inference")
endif()

# EXCLUDE_FROM_ALL: only what the bridge links gets built — not the CLI app or the tests.
add_subdirectory(${BERGAMOT_SOURCE_DIR} bergamot EXCLUDE_FROM_ALL)

# pathie-cpp calls iconv, which bionic only has from API 28. On Android the filesystem is always
# UTF-8, so pathie is told so, and the declarations it still references become weak so they resolve
# to jni/iconv_shim.c instead of failing to compile against minSdk 26.
target_compile_definitions(pathie-cpp PRIVATE PATHIE_ASSUME_UTF8_ON_UNIX __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)

add_library(dictate_bergamot SHARED
  jni/dictate_bergamot_jni.cpp
  jni/iconv_shim.c
)
target_link_libraries(dictate_bergamot PRIVATE bergamot-translator-source)

# Marian declares its build switches per directory — ARM/SSE/FMA route its SSE intrinsics through
# simd_utils' NEON shims, COMPILE_CPU/USE_RUY_SGEMM pick the backend. Bergamot's own sources and our
# bridge include the same Marian headers from other directories and never see them: on x86 the
# intrinsics exist anyway, on ARM every _mm_* call is undeclared. Hand them the same switches.
get_property(MARIAN_DEFINITIONS DIRECTORY "${BERGAMOT_SOURCE_DIR}/marian-fork" PROPERTY COMPILE_DEFINITIONS)
foreach(target bergamot-translator-source dictate_bergamot)
  target_compile_definitions(${target} PRIVATE ${MARIAN_DEFINITIONS} USE_SENTENCEPIECE _USE_INTERNAL_STRING_VIEW)
  target_compile_options(${target} PRIVATE -flax-vector-conversions)
endforeach()
target_link_options(dictate_bergamot PRIVATE
  -Wl,--gc-sections
  -Wl,--exclude-libs,ALL
  -Wl,-z,max-page-size=16384
)
