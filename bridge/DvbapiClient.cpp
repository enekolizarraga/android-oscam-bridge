// bridge/DvbapiClient.cpp
//
// Implementación del cliente TCP dvbapi con reconexión automática y backoff
// exponencial.  Compatible con POSIX (Android/Linux) y Winsock (para tests
// en Windows en esta fase).
//
// Hilo de red (networkThreadMain):
//   while (running_)
//     connectWithBackoff()   <- bloquea hasta conectar o agotar intentos
//     performHandshake()     <- CLIENT_INFO → SERVER_INFO
//     ioLoop()               <- select() con lectura y vaciado de cola de escritura
//     (si falla el socket: vuelve al inicio del while con backoff)

#include "include/DvbapiClient.h"
#include "include/BridgeLogger.h"

#include <algorithm>
#include <cassert>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <thread>

// ---------------------------------------------------------------------------
// Portabilidad POSIX / Winsock
// ---------------------------------------------------------------------------
#ifdef _WIN32
#  include <winsock2.h>
#  include <ws2tcpip.h>
#  pragma comment(lib, "Ws2_32.lib")
   using SockLen = int;
   using SocketFd = SOCKET;
#  define INVALID_SOCKET_FD  INVALID_SOCKET
#  define CLOSE_SOCKET(fd)   ::closesocket(fd)
#  define SOCK_ERRNO         WSAGetLastError()
#  define EAGAIN_EQUIV       WSAEWOULDBLOCK
// Inicialización de Winsock en el constructor del primer DvbapiClient.
namespace {
struct WinsockInit {
    WinsockInit()  { WSADATA w; WSAStartup(MAKEWORD(2,2), &w); }
    ~WinsockInit() { WSACleanup(); }
};
static WinsockInit gWinsockInit;
} // namespace
#else
#  include <arpa/inet.h>
#  include <fcntl.h>
#  include <netdb.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <sys/select.h>
#  include <sys/socket.h>
#  include <sys/types.h>
#  include <sys/un.h>
#  include <unistd.h>
   using SockLen = socklen_t;
   using SocketFd = int;
#  define INVALID_SOCKET_FD  (-1)
#  define CLOSE_SOCKET(fd)   ::close(fd)
#  define SOCK_ERRNO         errno
#  define EAGAIN_EQUIV       EAGAIN
#endif

namespace oscam::dvbapi {

// ---------------------------------------------------------------------------
// Constructor / Destructor
// ---------------------------------------------------------------------------

DvbapiClient::DvbapiClient(ConnectionConfig config, DvbapiCallbacks callbacks)
    : config_(std::move(config))
    , callbacks_(std::move(callbacks)) {
    recvBuf_.reserve(4096);
}

DvbapiClient::~DvbapiClient() {
    stop();
}

// ---------------------------------------------------------------------------
// start / stop
// ---------------------------------------------------------------------------

bool DvbapiClient::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) {
        BLOG_W("DvbapiClient::start() llamado cuando ya estaba corriendo");
        return true;
    }
    networkThread_ = std::thread(&DvbapiClient::networkThreadMain, this);
    BLOG_I("DvbapiClient iniciado (target: %s:%d, unix=%s)",
           config_.host.c_str(), config_.port, isUnixSocket() ? "true" : "false");
    return true;
}

void DvbapiClient::stop() {
    if (!running_.exchange(false)) return;

    // Despertar al hilo si está bloqueado en la cv de escritura.
    writeCv_.notify_all();

    if (networkThread_.joinable()) {
        networkThread_.join();
    }
    BLOG_I("DvbapiClient detenido");
}

bool DvbapiClient::isConnected() const noexcept {
    return connected_.load(std::memory_order_acquire);
}

// ---------------------------------------------------------------------------
// API pública de envío (thread-safe)
// ---------------------------------------------------------------------------

bool DvbapiClient::sendCaSetPid(uint8_t adapterId, const CaPid& pid) {
    if (!running_) return false;
    enqueueBytes(DvbapiProtocol::buildCaSetPid(adapterId, pid));
    return true;
}

bool DvbapiClient::sendDmxSetFilter(const DmxFilter& filter) {
    if (!running_) return false;
    enqueueBytes(DvbapiProtocol::buildDmxSetFilter(filter));
    return true;
}

bool DvbapiClient::sendDmxStop(uint8_t adapterId, uint8_t demuxId,
                                uint8_t filterId, uint16_t pid) {
    if (!running_) return false;
    enqueueBytes(DvbapiProtocol::buildDmxStop(adapterId, demuxId, filterId, pid));
    return true;
}

bool DvbapiClient::sendEcm(uint16_t serviceId, uint16_t /*caid*/, uint32_t /*providerId*/,
                           const uint8_t* ecmData, size_t length) {
    if (!running_) return false;
    CaPid caPid{};
    caPid.pid = serviceId > 0 ? serviceId : 0x0100;
    caPid.index = 0;
    sendCaSetPid(0, caPid);

    DmxFilter filter{};
    filter.adapterId = 0;
    filter.demuxId = 0;
    filter.filterId = 0;
    filter.pid = static_cast<uint16_t>(caPid.pid);
    if (ecmData && length > 0) {
        filter.filter[0] = ecmData[0];
        filter.mask[0] = 0xFF;
    } else {
        filter.filter[0] = 0x80;
        filter.mask[0] = 0xFE;
    }
    filter.flags = 0x01; // DMX_IMMEDIATE_START
    return sendDmxSetFilter(filter);
}

// ---------------------------------------------------------------------------
// Hilo de red
// ---------------------------------------------------------------------------

void DvbapiClient::networkThreadMain() {
    BLOG_I("Hilo de red iniciado");

    while (running_) {
        const int fd = connectWithBackoff();
        if (fd == INVALID_SOCKET_FD) {
            // Agotados los intentos → error fatal.
            BLOG_E("Agotados los intentos de conexión a %s:%d",
                   config_.host.c_str(), config_.port);
            if (callbacks_.OnFatalError) {
                callbacks_.OnFatalError("Agotados reintentos de conexión");
            }
            running_ = false;
            break;
        }

        connected_ = true;
        if (callbacks_.OnConnectionChanged) callbacks_.OnConnectionChanged(true);

        if (!performHandshake(fd)) {
            BLOG_W("Handshake fallido; reconectando...");
            CLOSE_SOCKET(fd);
            connected_ = false;
            if (callbacks_.OnConnectionChanged) callbacks_.OnConnectionChanged(false);
            continue;
        }

        ioLoop(fd);

        CLOSE_SOCKET(fd);
        connected_ = false;
        if (callbacks_.OnConnectionChanged) callbacks_.OnConnectionChanged(false);

        if (running_) {
            BLOG_W("Conexión perdida con %s:%d; reconectando...",
                   config_.host.c_str(), config_.port);
        }
    }

    BLOG_I("Hilo de red terminado");
}

// ---------------------------------------------------------------------------
// Conexión con backoff exponencial
// ---------------------------------------------------------------------------

int DvbapiClient::connectWithBackoff() {
    int backoffMs  = config_.initialBackoffMs;
    int attempts   = 0;
    const int maxA = config_.maxReconnectAttempts;

    while (running_) {
        if (maxA > 0 && attempts >= maxA) {
            return INVALID_SOCKET_FD;
        }

#if !defined(_WIN32)
        if (isUnixSocket()) {
            const int ufd = ::socket(AF_UNIX, SOCK_STREAM, 0);
            if (ufd < 0) {
                BLOG_E("socket(AF_UNIX) falló: error %d", SOCK_ERRNO);
                ++attempts;
                std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
                backoffMs = std::min(backoffMs * 2, config_.maxBackoffMs);
                continue;
            }
            setSocketTimeouts(ufd, config_.connectTimeoutSec);
            struct sockaddr_un sunAddr{};
            sunAddr.sun_family = AF_UNIX;
            std::strncpy(sunAddr.sun_path, config_.host.c_str(), sizeof(sunAddr.sun_path) - 1);
            if (::connect(ufd, reinterpret_cast<struct sockaddr*>(&sunAddr), sizeof(sunAddr)) == 0) {
                BLOG_I("Conectado a OSCam DVBAPI UNIX socket: %s (intento %d)", config_.host.c_str(), attempts + 1);
                return ufd;
            }
            BLOG_W("connect(UNIX: %s) falló (intento %d): error %d", config_.host.c_str(), attempts + 1, SOCK_ERRNO);
            CLOSE_SOCKET(ufd);
            ++attempts;
            std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
            backoffMs = std::min(backoffMs * 2, config_.maxBackoffMs);
            continue;
        }
#endif

        const int fd = createSocket();
        if (fd == INVALID_SOCKET_FD) {
            BLOG_E("createSocket() falló: error %d", SOCK_ERRNO);
            ++attempts;
            // Esperar antes de reintentar.
            std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
            backoffMs = std::min(backoffMs * 2, config_.maxBackoffMs);
            continue;
        }

        setSocketTimeouts(fd, config_.connectTimeoutSec);

        // Resolver dirección.
        struct addrinfo hints{};
        hints.ai_family   = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        hints.ai_protocol = IPPROTO_TCP;

        struct addrinfo* res = nullptr;
        const std::string portStr = std::to_string(config_.port);
        const int gaiErr = ::getaddrinfo(config_.host.c_str(),
                                         portStr.c_str(), &hints, &res);
        if (gaiErr != 0) {
            BLOG_E("getaddrinfo(%s) falló: %s",
                   config_.host.c_str(), gai_strerror(gaiErr));
            CLOSE_SOCKET(fd);
            ++attempts;
            std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
            backoffMs = std::min(backoffMs * 2, config_.maxBackoffMs);
            continue;
        }

        // Intentar conectar con cada dirección devuelta.
        bool connected = false;
        for (struct addrinfo* ai = res; ai != nullptr; ai = ai->ai_next) {
            const int ret = ::connect(fd, ai->ai_addr,
                                      static_cast<SockLen>(ai->ai_addrlen));
            if (ret == 0) {
                connected = true;
                break;
            }
            BLOG_W("connect() falló (intento %d): error %d", attempts + 1, SOCK_ERRNO);
        }
        ::freeaddrinfo(res);

        if (connected) {
            BLOG_I("Conectado a %s:%d (intento %d)",
                   config_.host.c_str(), config_.port, attempts + 1);
            // Activar TCP_NODELAY para reducir latencia del CW.
            int noDelay = 1;
            ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,
                         reinterpret_cast<const char*>(&noDelay), sizeof(noDelay));
            return fd;
        }

        CLOSE_SOCKET(fd);
        ++attempts;

        BLOG_W("Reintentando en %d ms (intento %d/%s)...",
               backoffMs, attempts,
               maxA > 0 ? std::to_string(maxA).c_str() : "∞");
        std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
        backoffMs = std::min(backoffMs * 2, config_.maxBackoffMs);
    }

    return INVALID_SOCKET_FD;
}

// ---------------------------------------------------------------------------
// Handshake CLIENT_INFO → SERVER_INFO
// ---------------------------------------------------------------------------

bool DvbapiClient::performHandshake(int fd) {
    ClientInfo ci;
    ci.protocolVersion = kProtocolVersion;
    ci.clientName      = "android-oscam-bridge";

    auto handshakeMsg = DvbapiProtocol::buildClientInfo(ci);
    const ssize_t sent = ::send(fd,
                                reinterpret_cast<const char*>(handshakeMsg.data()),
                                static_cast<int>(handshakeMsg.size()), 0);
    if (sent != static_cast<ssize_t>(handshakeMsg.size())) {
        BLOG_E("performHandshake: send() falló (sent=%zd, error=%d)",
               sent, SOCK_ERRNO);
        return false;
    }

    // Leer la respuesta SERVER_INFO (al menos 7 bytes).
    uint8_t headerBuf[7];
    const ssize_t recvd = ::recv(fd, reinterpret_cast<char*>(headerBuf),
                                 sizeof(headerBuf), MSG_WAITALL);
    if (recvd < static_cast<ssize_t>(sizeof(headerBuf))) {
        BLOG_E("performHandshake: recv() corto (%zd bytes, error=%d)",
               recvd, SOCK_ERRNO);
        return false;
    }

    // Verificar opcode.
    const uint32_t opcode =
        (static_cast<uint32_t>(headerBuf[0]) << 24) |
        (static_cast<uint32_t>(headerBuf[1]) << 16) |
        (static_cast<uint32_t>(headerBuf[2]) <<  8) |
         static_cast<uint32_t>(headerBuf[3]);

    if (opcode != DVBAPI_SERVER_INFO) {
        BLOG_E("performHandshake: opcode inesperado 0x%08X (esperaba SERVER_INFO)",
               opcode);
        return false;
    }

    // Leer el nombre del servidor si hay bytes adicionales.
    const uint8_t nameLen = headerBuf[6];
    std::vector<uint8_t> fullMsg(7 + nameLen);
    std::memcpy(fullMsg.data(), headerBuf, 7);

    if (nameLen > 0) {
        const ssize_t nameRecvd = ::recv(fd,
                                         reinterpret_cast<char*>(fullMsg.data() + 7),
                                         nameLen, MSG_WAITALL);
        if (nameRecvd != static_cast<ssize_t>(nameLen)) {
            BLOG_E("performHandshake: recv() nombre incompleto (%zd < %u)",
                   nameRecvd, nameLen);
            return false;
        }
    }

    auto si = DvbapiProtocol::parseServerInfo(
        std::span<const uint8_t>(fullMsg.data(), fullMsg.size()));
    if (!si) {
        BLOG_E("performHandshake: no se pudo parsear SERVER_INFO");
        return false;
    }

    BLOG_I("Handshake OK: servidor='%s' proto=%u",
           si->serverName.c_str(), si->protocolVersion);

    if (callbacks_.OnServerInfo) {
        callbacks_.OnServerInfo(*si);
    }
    return true;
}

// ---------------------------------------------------------------------------
// Bucle I/O principal (select + cola de escritura)
// ---------------------------------------------------------------------------

void DvbapiClient::ioLoop(int fd) {
    BLOG_D("ioLoop iniciado");
    setSocketTimeouts(fd, config_.recvTimeoutSec);

    while (running_) {
        // --- Vaciar cola de escritura ---
        if (!flushWriteQueue(fd)) {
            BLOG_W("ioLoop: error de escritura; cerrando socket");
            return;
        }

        // --- Esperar datos del servidor con select() (timeout = 1 s) ---
        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(static_cast<unsigned>(fd), &readSet);

        struct timeval tv{};
        tv.tv_sec  = 1;
        tv.tv_usec = 0;

        const int sel = ::select(fd + 1, &readSet, nullptr, nullptr, &tv);
        if (sel < 0) {
            BLOG_E("ioLoop: select() error %d", SOCK_ERRNO);
            return;
        }
        if (sel == 0) {
            // Timeout de select: volver al inicio del bucle (sin error).
            continue;
        }

        if (!readAndDispatch(fd)) {
            BLOG_W("ioLoop: error de lectura; cerrando socket");
            return;
        }
    }
    BLOG_D("ioLoop terminado");
}

// ---------------------------------------------------------------------------
// Escritura desde la cola
// ---------------------------------------------------------------------------

bool DvbapiClient::flushWriteQueue(int fd) {
    std::unique_lock<std::mutex> lock(writeMutex_);
    while (!writeQueue_.empty()) {
        auto data = std::move(writeQueue_.front());
        writeQueue_.pop();
        lock.unlock();

        size_t offset = 0;
        while (offset < data.size()) {
            const ssize_t sent = ::send(
                fd,
                reinterpret_cast<const char*>(data.data() + offset),
                static_cast<int>(data.size() - offset),
                0);
            if (sent <= 0) {
                BLOG_E("flushWriteQueue: send() falló (%zd, error=%d)",
                       sent, SOCK_ERRNO);
                return false;
            }
            offset += static_cast<size_t>(sent);
        }

        lock.lock();
    }
    return true;
}

// ---------------------------------------------------------------------------
// Lectura y dispatch de mensajes
// ---------------------------------------------------------------------------

bool DvbapiClient::readAndDispatch(int fd) {
    // Leer chunk en un buffer temporal.
    uint8_t chunk[4096];
    const ssize_t n = ::recv(fd, reinterpret_cast<char*>(chunk), sizeof(chunk), 0);
    if (n <= 0) {
        if (n == 0) {
            BLOG_I("readAndDispatch: conexión cerrada por el servidor");
        } else {
            BLOG_E("readAndDispatch: recv() error %d", SOCK_ERRNO);
        }
        return false;
    }

    // Acumular en el buffer de recepción.
    recvBuf_.insert(recvBuf_.end(), chunk, chunk + n);

    // Intentar parsear mensajes completos.
    while (recvBuf_.size() >= 4) {
        auto opcodeOpt = DvbapiProtocol::peekOpcode(
            std::span<const uint8_t>(recvBuf_.data(), recvBuf_.size()));
        if (!opcodeOpt) break;

        const uint32_t opcode = *opcodeOpt;
        size_t msgSize = DvbapiProtocol::expectedMessageSize(opcode);

        if (opcode == DVBAPI_SERVER_INFO) {
            // Tamaño variable: necesitamos al menos 7 bytes para saber nameLen.
            if (recvBuf_.size() < 7) break;
            const size_t nameLen = recvBuf_[6];
            msgSize = 7 + nameLen;
        }

        if (msgSize == 0) {
            // Opcode desconocido: no podemos avanzar sin saber el tamaño.
            BLOG_W("readAndDispatch: opcode desconocido 0x%08X; descartando buffer",
                   opcode);
            recvBuf_.clear();
            return true;
        }

        if (recvBuf_.size() < msgSize) {
            // Mensaje incompleto: esperar más bytes.
            BLOG_V("readAndDispatch: mensaje incompleto (%zu < %zu)",
                   recvBuf_.size(), msgSize);
            break;
        }

        // Despachar el mensaje completo.
        dispatchMessage(opcode,
                        std::span<const uint8_t>(recvBuf_.data(), msgSize));

        // Consumir los bytes del mensaje del buffer.
        recvBuf_.erase(recvBuf_.begin(),
                       recvBuf_.begin() + static_cast<ptrdiff_t>(msgSize));
    }

    // Protección contra buffer overflow.
    if (recvBuf_.size() > kRecvBufMax) {
        BLOG_E("readAndDispatch: buffer demasiado grande (%zu); limpiando",
               recvBuf_.size());
        recvBuf_.clear();
    }

    return true;
}

void DvbapiClient::dispatchMessage(uint32_t opcode,
                                   std::span<const uint8_t> payload) {
    BLOG_V("dispatchMessage: opcode=%s size=%zu",
           DvbapiProtocol::opcodeToString(opcode).c_str(), payload.size());

    switch (opcode) {
        case DVBAPI_CA_SET_DESCR: {
            auto d = DvbapiProtocol::parseCaSetDescr(payload);
            if (d && callbacks_.OnCaSetDescr) {
                callbacks_.OnCaSetDescr(*d);
            }
            break;
        }
        case DVBAPI_CA_SET_DESCR_MODE: {
            auto m = DvbapiProtocol::parseCaDescrMode(payload);
            if (m && callbacks_.OnCaDescrMode) {
                callbacks_.OnCaDescrMode(*m);
            }
            break;
        }
        case DVBAPI_SERVER_INFO: {
            auto si = DvbapiProtocol::parseServerInfo(payload);
            if (si && callbacks_.OnServerInfo) {
                callbacks_.OnServerInfo(*si);
            }
            break;
        }
        default:
            BLOG_W("dispatchMessage: opcode no manejado %s",
                   DvbapiProtocol::opcodeToString(opcode).c_str());
            break;
    }
}

// ---------------------------------------------------------------------------
// Helpers de socket
// ---------------------------------------------------------------------------

int DvbapiClient::createSocket() {
    const int fd = static_cast<int>(::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP));
    return fd;
}

bool DvbapiClient::setSocketTimeouts(int fd, int timeoutSec) {
#ifdef _WIN32
    const DWORD ms = static_cast<DWORD>(timeoutSec) * 1000;
    const int r1 = ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO,
                                 reinterpret_cast<const char*>(&ms), sizeof(ms));
    const int r2 = ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO,
                                 reinterpret_cast<const char*>(&ms), sizeof(ms));
#else
    struct timeval tv{};
    tv.tv_sec  = timeoutSec;
    tv.tv_usec = 0;
    const int r1 = ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    const int r2 = ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
#endif
    if (r1 != 0 || r2 != 0) {
        BLOG_W("setSocketTimeouts: setsockopt falló (r1=%d r2=%d)", r1, r2);
        return false;
    }
    return true;
}

void DvbapiClient::closeSocket(int fd) {
    CLOSE_SOCKET(fd);
}

void DvbapiClient::enqueueBytes(std::vector<uint8_t> data) {
    {
        std::lock_guard<std::mutex> lock(writeMutex_);
        writeQueue_.push(std::move(data));
    }
    writeCv_.notify_one();
}

} // namespace oscam::dvbapi
