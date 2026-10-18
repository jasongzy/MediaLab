cmake_minimum_required(VERSION 3.22.1)
project(medialab LANGUAGES C CXX)
include(ExternalProject)
set(JPEG_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/jpeg-src")
set(JPEG_BINARY "${CMAKE_CURRENT_BINARY_DIR}/jpeg-build")
ExternalProject_Add(jpeg
    URL "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libjpeg-turbo-3.2.0.tar.gz"
    URL_HASH SHA256=980dd81f425082aa6d7c9e47fef27554ce7a9ffc8e2f6e863b97d263c5c50858
    SOURCE_DIR "${JPEG_SOURCE}"
    BINARY_DIR "${JPEG_BINARY}"
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TOOLS=OFF -DWITH_TESTS=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target turbojpeg-static
    BUILD_BYPRODUCTS "${JPEG_BINARY}/libturbojpeg.a"
    INSTALL_COMMAND "")
add_library(turbojpeg STATIC IMPORTED)
set_target_properties(turbojpeg PROPERTIES IMPORTED_LOCATION "${JPEG_BINARY}/libturbojpeg.a")
add_library(medialab SHARED jpeg.cpp bitmap.cpp)
add_dependencies(medialab jpeg)
target_include_directories(medialab PRIVATE "${JPEG_SOURCE}/src" "${JPEG_BINARY}")
target_link_libraries(medialab PRIVATE turbojpeg jnigraphics)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF)
set(BUILD_BINDINGS OFF)
set(BUILD_TESTING OFF)
set(VISIBILITY_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party")
FetchContent_Declare(taglib_core
    URL "${THIRD_PARTY}/taglib-2.3.tar.gz"
    URL_HASH SHA256=7349f6fd942418bc7009ebe743eb7c9d055f02921ec56fa436ec25007c47fd38)
FetchContent_Declare(taglib_android
    URL "${THIRD_PARTY}/taglib-android-1.0.6.tar.gz"
    URL_HASH SHA256=8cbdf0ff9c2e4e3278ca149de5bf9f0bea2df7b8b99ec6a8e8e07e7dbf5f5526)
FetchContent_MakeAvailable(taglib_core taglib_android)

set(TAGLIB_JNI "${CMAKE_CURRENT_BINARY_DIR}/taglib-jni")
foreach(file taglib.cpp utils.h fileref_ext.cpp fileref_ext.h)
    configure_file("${taglib_android_SOURCE_DIR}/${file}" "${TAGLIB_JNI}/${file}" COPYONLY)
endforeach()
set(TAGLIB_PATCH "${THIRD_PARTY}/taglib-android-unicode.patch")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${TAGLIB_PATCH}")
find_package(Git REQUIRED)
# Apply independently of the enclosing repository so generated paths are included.
execute_process(COMMAND "${CMAKE_COMMAND}" -E env "GIT_CEILING_DIRECTORIES=${CMAKE_CURRENT_BINARY_DIR}"
    "${GIT_EXECUTABLE}" apply -p2 "${TAGLIB_PATCH}"
    WORKING_DIRECTORY "${TAGLIB_JNI}" COMMAND_ERROR_IS_FATAL ANY)

add_library(taglib SHARED "${TAGLIB_JNI}/taglib.cpp" "${TAGLIB_JNI}/fileref_ext.cpp")
get_target_property(TAGLIB_INCLUDES tag INCLUDE_DIRECTORIES)
target_include_directories(taglib PRIVATE ${TAGLIB_INCLUDES}
    "${taglib_core_SOURCE_DIR}/taglib" "${taglib_core_BINARY_DIR}/taglib")
target_link_libraries(taglib PRIVATE tag)
set_target_properties(taglib PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)
