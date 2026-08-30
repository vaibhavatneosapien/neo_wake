# neo_wake native codec (KTD3): builds the vendored libopus (see
# third_party/opus/VENDORING.md), the shared opus_bridge.h/.c decode-only
# wrapper (the SAME file neo_wake_ios compiles), and the JNI glue that binds
# it to NeoOpusDecoder.kt's `external fun`s.
#
# NOT built here: the mel/embed/classify state machine (WakeSpotter.kt) and
# the codec pipeline (WakeCodecPipeline.kt) — both pure Kotlin/JVM, no native
# code, verified via `gradle test` (see the U3 task report).
cmake_minimum_required(VERSION 3.18)
project(neo_wake_native C)

set(CMAKE_C_STANDARD 99)

# Match ios/Classes/ThirdParty/opus/VENDORING.md's build recipe exactly:
# HAVE_CONFIG_H selects the hand-written config.h (no autotools run here
# either), -w silences upstream's own warnings.
add_compile_definitions(HAVE_CONFIG_H=1 OPUS_BUILD=1)
add_compile_options(-w)

set(OPUS_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opus)

file(GLOB OPUS_CELT_SOURCES ${OPUS_ROOT}/celt/*.c)
file(GLOB OPUS_SILK_SOURCES ${OPUS_ROOT}/silk/*.c)
file(GLOB OPUS_SILK_FLOAT_SOURCES ${OPUS_ROOT}/silk/float/*.c)
set(OPUS_SRC_SOURCES
    ${OPUS_ROOT}/src/opus.c
    ${OPUS_ROOT}/src/opus_decoder.c
    ${OPUS_ROOT}/src/opus_encoder.c
    ${OPUS_ROOT}/src/repacketizer.c
    ${OPUS_ROOT}/src/analysis.c
    ${OPUS_ROOT}/src/mlp.c
    ${OPUS_ROOT}/src/mlp_data.c
)

add_library(opus STATIC
    ${OPUS_CELT_SOURCES}
    ${OPUS_SILK_SOURCES}
    ${OPUS_SILK_FLOAT_SOURCES}
    ${OPUS_SRC_SOURCES}
)
target_include_directories(opus PRIVATE
    ${OPUS_ROOT}
    ${OPUS_ROOT}/include
    ${OPUS_ROOT}/celt
    ${OPUS_ROOT}/silk
    ${OPUS_ROOT}/silk/float
)
# opus.h/opus_types.h/opus_defines.h are the only headers opus_bridge.c and
# consumers need — expose just the include/ dir publicly.
target_include_directories(opus PUBLIC ${OPUS_ROOT}/include)

add_library(neo_wake_native SHARED
    opus_bridge.c
    neo_opus_jni.c
)
target_link_libraries(neo_wake_native PRIVATE opus)
target_include_directories(neo_wake_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
