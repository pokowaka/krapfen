cmake_minimum_required(VERSION 3.22)
project(krapfen C)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(DOUGH_DIR ${THIRD_PARTY}/dough)
set(QJS_DIR ${THIRD_PARTY}/quickjs)

set(QJS_SOURCES
    ${QJS_DIR}/dtoa.c
    ${QJS_DIR}/libregexp.c
    ${QJS_DIR}/libunicode.c
    ${QJS_DIR}/quickjs.c
)

add_library(krapfen SHARED
    krapfen_jni.c
    krapfen_js.c
    dough_impl.c
    miniaudio_impl.c
    ${QJS_SOURCES}
)
target_include_directories(krapfen PRIVATE ${DOUGH_DIR} ${QJS_DIR})
target_compile_options(krapfen PRIVATE -std=gnu11)
target_compile_definitions(krapfen PRIVATE _GNU_SOURCE)

# Third-party sources are vendored unmodified; silence their warnings but
# keep our own code at full warning level.
set_source_files_properties(dough_impl.c miniaudio_impl.c ${QJS_SOURCES}
    PROPERTIES COMPILE_OPTIONS "-w")
set_source_files_properties(krapfen_jni.c krapfen_js.c
    PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra")

# miniaudio loads AAudio / OpenSL ES at runtime via dlopen.
target_link_libraries(krapfen PRIVATE log dl m)
