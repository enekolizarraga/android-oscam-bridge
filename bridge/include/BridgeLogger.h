// bridge/include/BridgeLogger.h
//
// Logging estructurado para la capa bridge.
//
// En Android: usa __android_log_print con el tag "OscamCasBridge".
// En host (tests / binario standalone): imprime a stderr con timestamp.
//
// El nivel activo se controla con OSCAM_LOG_LEVEL en tiempo de compilación
// o con BridgeLogger::setLevel() en tiempo de ejecución.
//
// Uso:
//   BLOG_INFO("Conexión establecida con %s:%d", host.c_str(), port);
//   BLOG_ERROR("Error de socket: %s", strerror(errno));
//
// Autor: android-oscam-bridge (Fase 1)

#pragma once

#include <cstdint>
#include <string>

namespace oscam {

/// Nivel de log (mismo orden que android/log.h ANDROID_LOG_*)
enum class LogLevel : int {
    Verbose = 2,
    Debug   = 3,
    Info    = 4,
    Warn    = 5,
    Error   = 6,
};

class BridgeLogger {
public:
    static void setLevel(LogLevel level) noexcept;
    static LogLevel level() noexcept;

    static void log(LogLevel level,
                    const char* tag,
                    const char* file,
                    int line,
                    const char* fmt, ...) __attribute__((format(printf, 5, 6)));
};

} // namespace oscam

// ---------------------------------------------------------------------------
// Macros de conveniencia
// ---------------------------------------------------------------------------

#define BLOG_TAG "OscamCasBridge"

#define BLOG_V(...) oscam::BridgeLogger::log(oscam::LogLevel::Verbose, BLOG_TAG, \
                        __FILE__, __LINE__, __VA_ARGS__)
#define BLOG_D(...) oscam::BridgeLogger::log(oscam::LogLevel::Debug,   BLOG_TAG, \
                        __FILE__, __LINE__, __VA_ARGS__)
#define BLOG_I(...) oscam::BridgeLogger::log(oscam::LogLevel::Info,    BLOG_TAG, \
                        __FILE__, __LINE__, __VA_ARGS__)
#define BLOG_W(...) oscam::BridgeLogger::log(oscam::LogLevel::Warn,    BLOG_TAG, \
                        __FILE__, __LINE__, __VA_ARGS__)
#define BLOG_E(...) oscam::BridgeLogger::log(oscam::LogLevel::Error,   BLOG_TAG, \
                        __FILE__, __LINE__, __VA_ARGS__)

#define BRIDGE_LOGV(...) BLOG_V(__VA_ARGS__)
#define BRIDGE_LOGD(...) BLOG_D(__VA_ARGS__)
#define BRIDGE_LOGI(...) BLOG_I(__VA_ARGS__)
#define BRIDGE_LOGW(...) BLOG_W(__VA_ARGS__)
#define BRIDGE_LOGE(...) BLOG_E(__VA_ARGS__)

