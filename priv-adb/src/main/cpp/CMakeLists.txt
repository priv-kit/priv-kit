cmake_minimum_required(VERSION 4.1)

project("privkitadb")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(
    -Werror=format
    -fdata-sections
    -ffunction-sections
    -fno-exceptions
    -fno-rtti
    -fno-threadsafe-statics
)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
endif ()

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

add_library(privkitadb SHARED
    privilege_adb_pairing.cpp
)

target_link_libraries(privkitadb
    log
    boringssl::crypto_static
    cxx::cxx
)

add_executable(libprivkitstarter.so
    privilege_adb_starter.cpp
)

target_link_libraries(libprivkitstarter.so
    log
)
