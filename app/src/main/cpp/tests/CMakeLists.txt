cmake_minimum_required(VERSION 3.22)
project(audiophile_native_core_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(audiophile_native_core_tests
    native_core_tests.cpp
    ../pcm_wire_formatter.cpp
    ../usb_playback_state.cpp
    ../dop_formatter.cpp
    ../native_dsd_formatter.cpp
    ../audio_analysis_aggregator.cpp
    ../audio_integral_aggregator.cpp
)

target_include_directories(audiophile_native_core_tests PRIVATE ..)
target_compile_options(audiophile_native_core_tests PRIVATE
    -Wall
    -Wextra
    -Werror
    -pedantic
)

option(AUDIPHILE_ENABLE_SANITIZERS "Enable host address/undefined sanitizers" ON)
if(AUDIPHILE_ENABLE_SANITIZERS AND CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(audiophile_native_core_tests PRIVATE
        -fsanitize=address,undefined
        -fno-omit-frame-pointer
    )
    target_link_options(audiophile_native_core_tests PRIVATE
        -fsanitize=address,undefined
    )
endif()
