# ─────────────────────────────────────────────────────────────────────────────
# Audiophile Native — FFmpeg JNI decoder + libusb USB output engine
#
# Step 15: Legacy AudioTrack output has been severed from the FFmpeg decode
# pipeline.  The new architecture routes decoded PCM / DSD bytes through:
#
#   FfmpegAudioDecoder (IAudioDecoder)
#       └─ DecoderToRingBridge (pump thread)
#              └─ SpscRingBuffer
#                     └─ IsoTransferPool ISO callback → USB DAC
#
# New source files added by this step:
#   i_audio_decoder.h           — pure C++ decoder interface (header only)
#   ffmpeg_audio_decoder.h/.cpp — FFmpeg implementation of IAudioDecoder
#   decoder_to_ring_bridge.h/.cpp — pump thread: decoder → SpscRingBuffer
#   engine_swap_bridge.cpp      — JNI for ACTION_USB_DEVICE_ATTACHED hot-plug
#
# Two-mode build:
#  - If app/src/main/jniLibs/<ABI>/libavformat.so exists, the full bridge
#    (ffmpeg_bridge.cpp + sue_bridge.cpp + Step-15 files) is compiled.
#  - Otherwise, the stub (ffmpeg_bridge_stub.cpp) is compiled so the APK
#    still assembles. Decoding will fail at runtime with a clear
#    FFmpegDecoderException.
#
# SoX resampler — embedded entirely inside FFmpeg lavfi:
#  - FFmpeg is compiled with --enable-libsoxr, so the soxr VHQ engine is
#    embedded inside libswresample.so and exposed via the lavfi aresample
#    filter (aresample=osr=...:resampler=soxr:precision=33).
#  - There is no separate soxr JNI bridge; resampling is handled inside the
#    decoder's lavfi graph (ffmpeg_bridge.cpp).
#  - No libsoxr.a / soxr.h is required at build time.
#
# Single Source of Truth layout:
#  - Shared libraries  →  app/src/main/jniLibs/<ABI>/lib<name>.so
#  - C/C++ headers     →  app/src/main/cpp/include/  (ABI-agnostic)
#
# Drop .so files into jniLibs/<ABI>/ and rebuild — CMake auto-detects.
# ─────────────────────────────────────────────────────────────────────────────
cmake_minimum_required(VERSION 3.22.1)
project(audiophile_native LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Release-build aggressive optimisations ───────────────────────────────────
string(APPEND CMAKE_CXX_FLAGS_RELEASE " -O3 -flto")
string(APPEND CMAKE_C_FLAGS_RELEASE   " -O3 -flto")

# ── Library and header paths — Single Source of Truth ────────────────────────
set(FFMPEG_LIB      ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(FFMPEG_INCLUDE  ${CMAKE_SOURCE_DIR}/include)

# ── Feature detection ─────────────────────────────────────────────────────────
set(FFMPEG_AVAILABLE FALSE)

if(EXISTS "${FFMPEG_LIB}/libavformat.so")
    set(FFMPEG_AVAILABLE TRUE)
endif()

find_library(log-lib     log)
find_library(android-lib android)

set(AUDIPHILE_TRANSPORT_SOURCES
    cpu_affinity_policy.cpp
    decoder_to_ring_bridge.cpp
    engine_swap_bridge.cpp
    usb_audio_bridge.cpp
    usb_session_transport.cpp
    usb_teardown.cpp
    uac2_descriptor_parser.cpp
    uac2_dsd_detector.cpp
    uac2_clock_control.cpp
    usb_device_controller.cpp
    usb_iso_transfer_pool.cpp
    spsc_ring_buffer.cpp
    pcm_wire_formatter.cpp
    usb_playback_state.cpp
    usb_producer_coordinator.cpp
    dop_formatter.cpp
    native_dsd_formatter.cpp
    dsd_playback_manager.cpp
    libusb_event_thread.cpp
)

# ── libusb prebuilt (user-space USB Audio driver) ────────────────────────────
# libusb1.0.so is bundled in jniLibs/<ABI>/ for both arm64-v8a and x86_64.
# The library is imported as a SHARED target so CMake packages it into the APK
# and produces the correct DT_NEEDED entry in audiophile_native's ELF header.
add_library(libusb SHARED IMPORTED)
set_target_properties(libusb PROPERTIES
    IMPORTED_LOCATION ${FFMPEG_LIB}/libusb1.0.so
    IMPORTED_NO_SONAME TRUE
)

if(FFMPEG_AVAILABLE)
    message(STATUS "Audiophile: FFmpeg prebuilt found for ${ANDROID_ABI} — building full bridge")
    message(STATUS "Audiophile: soxr VHQ resampler embedded in lavfi (--enable-libsoxr FFmpeg build)")

    foreach(_lib avformat avcodec avfilter swresample avutil)
        add_library(${_lib} SHARED IMPORTED)
        set_target_properties(${_lib} PROPERTIES
            IMPORTED_LOCATION ${FFMPEG_LIB}/lib${_lib}.so
            IMPORTED_NO_SONAME TRUE
        )
    endforeach()

    add_library(audiophile_native SHARED
        ffmpeg_bridge.cpp
        ffmpeg_audio_decoder.cpp
        sue_bridge.cpp
        ${AUDIPHILE_TRANSPORT_SOURCES}
    )

    target_include_directories(audiophile_native PRIVATE
        ${FFMPEG_INCLUDE}
    )

    target_compile_options(audiophile_native PRIVATE
        -Wall -Wextra -Werror=return-type -Wno-unused-parameter
        $<$<CONFIG:Release>:-O3 -flto>
    )

    target_link_libraries(audiophile_native PRIVATE
        avformat
        avcodec
        avfilter
        swresample
        avutil
        libusb
        ${log-lib}
        ${android-lib}
    )

    target_link_options(audiophile_native PRIVATE
        $<$<CONFIG:Release>:-flto>
    )

else()
    message(WARNING
        "Audiophile: FFmpeg prebuilt NOT found at ${FFMPEG_LIB}/libavformat.so — "
        "building stub .so.  Decoding will fail at runtime with a provisioning "
        "error; drop the FFmpeg .so files in and rebuild to activate the full bridge.")

    add_library(audiophile_native SHARED
        ffmpeg_bridge_stub.cpp
        ${AUDIPHILE_TRANSPORT_SOURCES}
    )

    target_compile_options(audiophile_native PRIVATE
        -Wall -Wextra -Werror=return-type -Wno-unused-parameter
        $<$<CONFIG:Release>:-O3 -flto>
    )
    target_link_options(audiophile_native PRIVATE
        $<$<CONFIG:Release>:-flto>
    )
    target_link_libraries(audiophile_native PRIVATE ${log-lib} ${android-lib} libusb)
endif()
