# Local LLM (PLAN 9.1, D-019): llama.cpp at the pinned commit (tools/fetch_llama.sh) + a small JNI layer.
# Flags match the Phase 0 benchmark build (docs/BENCHMARKS.md).
cmake_minimum_required(VERSION 3.22.1)
project(jarvis_llm C CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+dotprod")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(jarvis_llm SHARED jarvis_llm.cpp)
target_link_libraries(jarvis_llm llama log android)
target_link_options(jarvis_llm PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
