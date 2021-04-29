# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.10.2)
project("cmaketest")
#add_definitions(-DDEBUG)
add_library(audiolat SHARED aaudio.cpp oboe.cpp)
add_library(audiolat_sdk28  SHARED  aaudio.cpp oboe.cpp)

find_library(log-lib log)
find_library(aaudio-lib aaudio)

find_package (oboe REQUIRED CONFIG)
target_link_libraries(audiolat
                        ${log-lib}
                        ${aaudio-lib}
                        amidi
                        oboe::oboe)
target_link_libraries(audiolat_sdk28
                        ${log-lib}
                        ${aaudio-lib}
                        oboe::oboe)