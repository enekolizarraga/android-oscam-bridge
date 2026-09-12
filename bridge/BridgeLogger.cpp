// bridge/BridgeLogger.cpp
//
// Implementación del logger portable (stderr en host, logcat en Android).

#include "include/BridgeLogger.h"

#include <cstdarg>
#include <cstdio>
#include <ctime>
#include <atomic>
#include <string>

// En Android usamos __android_log_vprint; en host lo simulamos.
#ifdef __ANDROID__
#  include <android/log.h>
#  define PLATFORM_ANDROID 1
#else
#  define PLATFORM_ANDROID 0
#endif

namespace oscam {

namespace {
// Nivel mínimo activo (thread-safe por ser atomic; solo lectura mayoritaria).
std::atomic<int> gMinLevel{static_cast<int>(LogLevel::Debug)};

/// Prefijo de nivel para el output de host.
const char* levelPrefix(LogLevel lvl) noexcept {
    switch (lvl) {
        case LogLevel::Verbose: return "V";
        case LogLevel::Debug:   return "D";
        case LogLevel::Info:    return "I";
        case LogLevel::Warn:    return "W";
        case LogLevel::Error:   return "E";
        default:                return "?";
    }
}

/// Timestamp ISO-8601 simplificado para logs de host.
std::string hostTimestamp() {
    std::time_t t = std::time(nullptr);
    char buf[32];
    // strftime es thread-safe en POSIX cuando se llama con buffers independientes.
    std::strftime(buf, sizeof(buf), "%H:%M:%S", std::localtime(&t));
    return buf;
}

} // namespace

void BridgeLogger::setLevel(LogLevel level) noexcept {
    gMinLevel.store(static_cast<int>(level), std::memory_order_relaxed);
}

LogLevel BridgeLogger::level() noexcept {
    return static_cast<LogLevel>(gMinLevel.load(std::memory_order_relaxed));
}

void BridgeLogger::log(LogLevel lvl,
                       const char* tag,
                       const char* /*file*/,
                       int /*line*/,
                       const char* fmt, ...) {
    if (static_cast<int>(lvl) < gMinLevel.load(std::memory_order_relaxed)) {
        return;
    }

    va_list args;
    va_start(args, fmt);

#if PLATFORM_ANDROID
    // Delegar directamente a logcat; __android_log_vprint lleva timestamp.
    __android_log_vprint(static_cast<int>(lvl), tag, fmt, args);
#else
    // Host: imprimir a stderr con timestamp y nivel.
    // Dos pasos porque va_list solo se puede recorrer una vez.
    char msgBuf[2048];
    std::vsnprintf(msgBuf, sizeof(msgBuf), fmt, args);
    std::fprintf(stderr, "[%s] %s/%s: %s\n",
                 hostTimestamp().c_str(),
                 levelPrefix(lvl),
                 tag,
                 msgBuf);
    std::fflush(stderr);
#endif

    va_end(args);
}

} // namespace oscam
