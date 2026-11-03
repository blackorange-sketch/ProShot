cmake_minimum_required(VERSION 3.18.1)
project(proshot_detail CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(proshot_detail SHARED stacker_core.cpp jni_bridge.cpp)
target_compile_options(proshot_detail PRIVATE -O3 -fno-exceptions -fno-rtti)
