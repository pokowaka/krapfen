cmake_minimum_required(VERSION 3.22)
project(krapfen C)

set(DOUGH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dough)

add_library(krapfen SHARED
    krapfen_jni.c
    dough_impl.c
    miniaudio_impl.c
)
target_include_directories(krapfen PRIVATE ${DOUGH_DIR})
target_compile_options(krapfen PRIVATE -std=gnu11)

# Third-party implementation units are vendored unmodified; silence their
# warnings but keep our own code (krapfen_jni.c) at full warning level.
set_source_files_properties(dough_impl.c miniaudio_impl.c
    PROPERTIES COMPILE_OPTIONS "-w")
set_source_files_properties(krapfen_jni.c
    PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra")

# miniaudio loads AAudio / OpenSL ES at runtime via dlopen.
target_link_libraries(krapfen PRIVATE log dl m)
