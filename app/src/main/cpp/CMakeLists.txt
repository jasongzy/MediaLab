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
FetchContent_Declare(libraw
    URL "${THIRD_PARTY}/LibRaw-${LIBRAW_VERSION}.tar.gz"
    URL_HASH SHA256=de86b035655accff8d4010f1a221fdf50d353cb7b1422ba26f14a0db92612cfa)
FetchContent_MakeAvailable(libraw)
file(READ "${libraw_SOURCE_DIR}/Makefile.am" LIBRAW_MAKEFILE)
string(REGEX MATCHALL "src/[a-zA-Z0-9_/]+\\.cpp" LIBRAW_SOURCES "${LIBRAW_MAKEFILE}")
list(TRANSFORM LIBRAW_SOURCES PREPEND "${libraw_SOURCE_DIR}/")
add_library(medialab_raw SHARED raw.cpp ${LIBRAW_SOURCES})
add_dependencies(medialab_raw jpeg)
target_include_directories(medialab_raw PRIVATE "${libraw_SOURCE_DIR}" "${JPEG_SOURCE}/src" "${JPEG_BINARY}")
target_compile_definitions(medialab_raw PRIVATE LIBRAW_NODLL USE_JPEG USE_JPEG8 USE_ZLIB)
target_link_libraries(medialab_raw PRIVATE turbojpeg jnigraphics z)
set_target_properties(medialab_raw PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)

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
set(TAGLIB_PATCHES "${THIRD_PARTY}/taglib-android-unicode.patch" "${THIRD_PARTY}/taglib-android-format.patch")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${TAGLIB_PATCHES})
find_package(Git REQUIRED)
# Apply independently of the enclosing repository so generated paths are included.
execute_process(COMMAND "${CMAKE_COMMAND}" -E env "GIT_CEILING_DIRECTORIES=${CMAKE_CURRENT_BINARY_DIR}"
    "${GIT_EXECUTABLE}" apply -p2 ${TAGLIB_PATCHES}
    WORKING_DIRECTORY "${TAGLIB_JNI}" COMMAND_ERROR_IS_FATAL ANY)

add_library(taglib SHARED "${TAGLIB_JNI}/taglib.cpp" "${TAGLIB_JNI}/fileref_ext.cpp")
get_target_property(TAGLIB_INCLUDES tag INCLUDE_DIRECTORIES)
target_include_directories(taglib PRIVATE ${TAGLIB_INCLUDES}
    "${taglib_core_SOURCE_DIR}/taglib" "${taglib_core_BINARY_DIR}/taglib")
target_link_libraries(taglib PRIVATE tag)
set_target_properties(taglib PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)

FetchContent_Declare(mnn_runtime
    URL "${THIRD_PARTY}/mnn-${MNN_VERSION}-arm64.tar.gz"
    URL_HASH SHA256=35d0495833a3bd1d0241588a4fc22e1423b9b474f18384cf5701461b69c94ef1)
FetchContent_MakeAvailable(mnn_runtime)
foreach(name MNN MNN_CL)
    add_library(${name} SHARED IMPORTED)
    set_target_properties(${name} PROPERTIES IMPORTED_LOCATION "${mnn_runtime_SOURCE_DIR}/lib/lib${name}.so")
endforeach()
add_library(medialab_mnn SHARED background.cpp)
target_include_directories(medialab_mnn PRIVATE "${mnn_runtime_SOURCE_DIR}/include")
target_link_libraries(medialab_mnn PRIVATE MNN MNN_CL)
