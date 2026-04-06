cmake_minimum_required(VERSION 3.22.1)
project("nodebridge")

# Node.js headers from libnode.so package
include_directories(${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/include/node)

# Import pre-built libnode.so
add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so
)

# Our JNI bridge
add_library(nodebridge SHARED native-bridge.cpp)

target_link_libraries(nodebridge
    node
    log
    android
)
