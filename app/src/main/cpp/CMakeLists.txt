cmake_minimum_required(VERSION 3.22.1)
project(android_tux_pty)

add_library(android_tux_pty SHARED native-lib.cpp)

target_compile_features(android_tux_pty PRIVATE cxx_std_17)
target_compile_options(android_tux_pty PRIVATE -Wall -Wextra -Werror=return-type)
