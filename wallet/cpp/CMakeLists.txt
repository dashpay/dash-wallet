cmake_minimum_required(VERSION 3.6.0)
add_library(x11 SHARED aes_helper.c blake.c bmw.c cubehash.c echo.c groestl.c hashblock.cpp keccak.c jh.c luffa.c shavite.c simd.c skein.c)
include_directories(.)

add_subdirectory(dashj-bls)
