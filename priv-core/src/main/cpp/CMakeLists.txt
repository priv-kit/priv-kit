cmake_minimum_required(VERSION 4.1)

project("privkitcore")

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

add_executable(libprivkitstarter.so
    priv_kit_starter.cpp
)

target_compile_features(libprivkitstarter.so PRIVATE cxx_std_20)

target_sources(libprivkitstarter.so
    PRIVATE FILE_SET CXX_MODULES FILES priv_kit_user_service.cppm
)
