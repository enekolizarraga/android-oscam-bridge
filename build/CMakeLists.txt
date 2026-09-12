cmake_minimum_required(VERSION 3.18)
project(android-oscam-bridge CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Flags de compilación (reproduce -Wall -Wextra del NDK Android)
# ---------------------------------------------------------------------------
if(MSVC)
    add_compile_options(/W4)
else()
    add_compile_options(
        -Wall -Wextra -Wpedantic -Wshadow
        -Wno-unused-parameter
    )
endif()

# ---------------------------------------------------------------------------
# 1. Biblioteca estática bridge (Fase 1)
# ---------------------------------------------------------------------------
add_library(oscam_bridge STATIC
    bridge/BridgeLogger.cpp
    bridge/DvbapiProtocol.cpp
    bridge/DvbapiClient.cpp
    bridge/NewcamdClient.cpp
    bridge/CCcamClient.cpp
    bridge/SatellitePmtParser.cpp
    bridge/SoftwareDescrambler.cpp
)

target_include_directories(oscam_bridge PUBLIC
    bridge
)

if(WIN32)
    target_link_libraries(oscam_bridge PUBLIC ws2_32)
endif()

if(ANDROID)
    target_link_libraries(oscam_bridge PUBLIC log)
endif()

# ---------------------------------------------------------------------------
# 2. Capa de abstracción de chipset (Fase 2)
# ---------------------------------------------------------------------------
add_library(oscam_chipset STATIC
    chipset/AmlogicAdapter.cpp
    chipset/MediaTekAdapter.cpp
    chipset/RealtekAdapter.cpp
    chipset/BroadcomAdapter.cpp
    chipset/SynapticsAdapter.cpp
    chipset/NovatekAdapter.cpp
    chipset/ChipsetDetector.cpp
)

target_include_directories(oscam_chipset PUBLIC
    chipset
    bridge
)

target_link_libraries(oscam_chipset PUBLIC oscam_bridge)

# ---------------------------------------------------------------------------
# 3. Capa HAL CAS agnóstica (Fase 3)
# ---------------------------------------------------------------------------
add_library(oscam_hal STATIC
    hal/native/OscamCasPlugin.cpp
    hal/native/OscamCasService.cpp
    hal/native/ConfigWatcher.cpp
)

target_include_directories(oscam_hal PUBLIC
    hal/native
    chipset
    bridge
)

target_link_libraries(oscam_hal PUBLIC oscam_chipset oscam_bridge)

# ---------------------------------------------------------------------------
# 4. Biblioteca compartida JNI para la app Android Kotlin (Fase 4)
# ---------------------------------------------------------------------------
if(ANDROID)
    add_library(oscam_jni SHARED
        jni/com_oscam_cas_OscamCasPlugin.cpp
    )
    target_include_directories(oscam_jni PUBLIC
        jni
        bridge
        hal/native
        chipset
    )
    target_link_libraries(oscam_jni PRIVATE
        oscam_bridge
        oscam_chipset
        oscam_hal
        log
    )
else()
    find_package(JNI QUIET)
    if(JNI_FOUND)
        add_library(oscam_jni SHARED
            jni/com_oscam_cas_OscamCasPlugin.cpp
        )
        target_include_directories(oscam_jni PUBLIC
            jni
            bridge
            hal/native
            chipset
            ${JNI_INCLUDE_DIRS}
        )
        target_link_libraries(oscam_jni PRIVATE
            oscam_bridge
            oscam_chipset
            oscam_hal
        )
        if(WIN32)
            target_link_libraries(oscam_jni PRIVATE ws2_32)
        endif()
    endif()
endif()

# ---------------------------------------------------------------------------
# Binario de prueba standalone (Fase 1)
# ---------------------------------------------------------------------------
add_executable(oscam_bridge_test
    bridge/oscam_bridge_test_main.cpp
)
target_link_libraries(oscam_bridge_test PRIVATE oscam_bridge)

# ---------------------------------------------------------------------------
# Tests unitarios con GoogleTest (Fases 1, 2 y 3)
# ---------------------------------------------------------------------------
if(NOT ANDROID)
    find_package(GTest QUIET)

    if(NOT GTest_FOUND)
        message(STATUS "GTest no encontrado en el sistema; descargando via FetchContent")
        include(FetchContent)
        FetchContent_Declare(
            googletest
            GIT_REPOSITORY https://github.com/google/googletest.git
            GIT_TAG        v1.14.0
        )
        set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
        FetchContent_MakeAvailable(googletest)
    endif()

    # Tests Fase 1
    add_executable(protocol_tests
        tests/DvbapiProtocolTest.cpp
    )
    target_link_libraries(protocol_tests PRIVATE
        oscam_bridge
        GTest::gtest
        GTest::gtest_main
    )

    # Tests Fase 2
    add_executable(chipset_tests
        tests/ChipsetDetectorTest.cpp
    )
    target_link_libraries(chipset_tests PRIVATE
        oscam_chipset
        GTest::gtest
        GTest::gtest_main
    )

    # Tests Fase 3
    add_executable(hal_tests
        tests/OscamCasPluginTest.cpp
    )
    target_link_libraries(hal_tests PRIVATE
        oscam_hal
        GTest::gtest
        GTest::gtest_main
    )

    # Tests Newcamd v5.25 Client & 3DES Crypto
    add_executable(newcamd_tests
        tests/NewcamdClientTest.cpp
    )
    target_link_libraries(newcamd_tests PRIVATE
        oscam_bridge
        GTest::gtest
        GTest::gtest_main
    )

    # Tests CCcam v2.3.0 Client & RC4/SHA1 Crypto
    add_executable(cccam_tests
        tests/CCcamClientTest.cpp
    )
    target_link_libraries(cccam_tests PRIVATE
        oscam_bridge
        GTest::gtest
        GTest::gtest_main
    )

    enable_testing()
    include(GoogleTest)
    gtest_discover_tests(protocol_tests)
    gtest_discover_tests(chipset_tests)
    gtest_discover_tests(hal_tests)
    gtest_discover_tests(newcamd_tests)
    gtest_discover_tests(cccam_tests)
endif()

# ---------------------------------------------------------------------------
# Instalación
# ---------------------------------------------------------------------------
install(TARGETS oscam_bridge oscam_chipset oscam_hal
    ARCHIVE DESTINATION lib
)
install(DIRECTORY bridge/include/ chipset/include/ hal/native/include/
    DESTINATION include/oscam
    FILES_MATCHING PATTERN "*.h"
)
install(FILES hal/manifest/cas_config.xml
    DESTINATION etc/cas
)
install(FILES hal/manifest/android.hardware.cas.xml
    DESTINATION etc/vintf/manifest
)
