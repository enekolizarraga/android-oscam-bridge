package com.lizarragaeus.oscambridge.http

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 100% Android-compatible embedded HTTP server implemented purely with standard
 * java.net.ServerSocket and java.net.Socket.
 *
 * Replaces the desktop-only com.sun.net.httpserver.HttpServer (which relies on
 * sun.misc.Service and is absent on Android runtime / ART / Dalvik).
 *
 * Provides drop-in replacements for:
 *   - HttpServer
 *   - HttpExchange
 *   - HttpHandler
 *   - Headers
 *
 * Package ID: com.lizarragaeus.oscambridge
 * Author: Eneko Lizarraga
 */

class Headers : java.util.TreeMap<String, MutableList<String>>(java.lang.String.CASE_INSENSITIVE_ORDER) {
    fun getFirst(key: String): String? {
        return this[key]?.firstOrNull()
    }

    fun set(key: String, value: String) {
        this[key] = mutableListOf(value)
    }

    fun add(key: String, value: String) {
        val list = this[key]
        if (list != null) {
            list.add(value)
        } else {
            this[key] = mutableListOf(value)
        }
    }
}

fun interface HttpHandler {
    fun handle(exchange: HttpExchange)
}

class HttpExchange(
    private val socket: Socket,
    val requestMethod: String,
    val requestURI: URI,
    val requestHeaders: Headers,
    val requestBody: InputStream
) {
    val responseHeaders: Headers = Headers()
    private var headersSent = false
    private val rawOut = socket.getOutputStream()

    val responseBody: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            ensureHeadersSent()
            rawOut.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            ensureHeadersSent()
            rawOut.write(b, off, len)
        }

        override fun flush() {
            rawOut.flush()
        }

        override fun close() {
            try {
                flush()
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    fun sendResponseHeaders(rCode: Int, responseLength: Long) {
        if (headersSent) return
        headersSent = true

        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(rCode).append(" ").append(getStatusText(rCode)).append("\r\n")

        if (!responseHeaders.containsKey("Access-Control-Allow-Origin")) {
            responseHeaders.set("Access-Control-Allow-Origin", "*")
        }
        if (!responseHeaders.containsKey("Access-Control-Allow-Methods")) {
            responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
        }
        if (!responseHeaders.containsKey("Access-Control-Allow-Headers")) {
            responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization, *")
        }

        for ((key, values) in responseHeaders) {
            for (v in values) {
                sb.append(key).append(": ").append(v).append("\r\n")
            }
        }

        if (responseLength > 0) {
            sb.append("Content-Length: ").append(responseLength).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")

        rawOut.write(sb.toString().toByteArray(Charsets.UTF_8))
        rawOut.flush()
    }

    private fun ensureHeadersSent() {
        if (!headersSent) {
            sendResponseHeaders(200, 0)
        }
    }

    fun close() {
        try {
            responseBody.close()
        } catch (_: Exception) {}
    }

    companion object {
        fun getStatusText(code: Int): String = when (code) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "OK"
        }
    }
}

class HttpServer private constructor(private val address: InetSocketAddress) {
    companion object {
        private const val TAG = "AndroidHttpServer"

        fun create(addr: InetSocketAddress, backlog: Int = 0): HttpServer {
            return HttpServer(addr)
        }
    }

    var executor: Executor? = null
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val contexts = ConcurrentHashMap<String, HttpHandler>()
    private val clientPool = Executors.newCachedThreadPool()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    fun createContext(path: String, handler: HttpHandler) {
        contexts[path] = handler
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(address)
        serverSocket = ss

        val listenerThread = Thread({
            while (isRunning && !ss.isClosed) {
                try {
                    val socket = ss.accept()
                    socket.soTimeout = 15000 // 15s read timeout
                    activeSockets.add(socket)
                    val exec = executor ?: clientPool
                    exec.execute {
                        try {
                            handleClient(socket)
                        } catch (e: Exception) {
                            Log.d(TAG, "Client handling exception: ${e.message}")
                        } finally {
                            activeSockets.remove(socket)
                        }
                    }
                } catch (_: SocketException) {
                    // Socket closed on stop
                    break
                } catch (e: Exception) {
                    if (!isRunning) break
                    Log.w(TAG, "Server accept error: ${e.message}")
                }
            }
        }, "AndroidHttpServer-${address.port}")
        listenerThread.isDaemon = true
        listenerThread.start()
    }

    @Synchronized
    fun stop(delay: Int = 0) {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        for (sock in activeSockets) {
            try { sock.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
        clientPool.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        val rawIn = socket.getInputStream()
        val requestLine = readLine(rawIn) ?: run {
            try { socket.close() } catch (_: Exception) {}
            return
        }

        val parts = requestLine.split(" ")
        if (parts.isEmpty()) {
            try { socket.close() } catch (_: Exception) {}
            return
        }
        val method = parts[0]
        val uriStr = if (parts.size > 1) parts[1] else "/"
        val uri = try { URI(uriStr) } catch (_: Exception) { URI("/") }

        val requestHeaders = Headers()
        while (true) {
            val headerLine = readLine(rawIn) ?: break
            if (headerLine.isEmpty()) break
            val idx = headerLine.indexOf(':')
            if (idx > 0) {
                val key = headerLine.substring(0, idx).trim()
                val value = headerLine.substring(idx + 1).trim()
                requestHeaders.add(key, value)
            }
        }

        if (method.equals("OPTIONS", ignoreCase = true)) {
            val exchange = HttpExchange(socket, method, uri, requestHeaders, BoundedInputStream(rawIn, 0))
            exchange.sendResponseHeaders(204, 0)
            exchange.responseBody.close()
            return
        }

        val contentLength = requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0L
        val requestBody = BoundedInputStream(rawIn, contentLength)

        val exchange = HttpExchange(socket, method, uri, requestHeaders, requestBody)

        val handler = findHandler(uri.path ?: "/")
        if (handler != null) {
            try {
                handler.handle(exchange)
            } catch (e: Exception) {
                Log.e(TAG, "Error in handler for ${uri.path}: ${e.message}", e)
                try {
                    exchange.sendResponseHeaders(500, 0)
                    exchange.responseBody.close()
                } catch (_: Exception) {}
            }
        } else {
            val notFoundBytes = "404 Not Found".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(404, notFoundBytes.size.toLong())
            exchange.responseBody.write(notFoundBytes)
            exchange.responseBody.close()
        }
    }

    private fun findHandler(path: String): HttpHandler? {
        val exact = contexts[path]
        if (exact != null) return exact
        return contexts.entries
            .filter { path.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    private fun readLine(inputStream: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = inputStream.read()
            if (b == -1) {
                if (baos.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.ISO_8859_1)
            }
            baos.write(b)
        }
        return String(baos.toByteArray(), Charsets.ISO_8859_1)
    }

    private class BoundedInputStream(
        private val wrapped: InputStream,
        private val maxBytes: Long
    ) : InputStream() {
        private var bytesRead: Long = 0

        override fun read(): Int {
            if (maxBytes > 0 && bytesRead >= maxBytes) return -1
            if (maxBytes == 0L && wrapped.available() <= 0) return -1
            val b = wrapped.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (maxBytes > 0 && bytesRead >= maxBytes) return -1
            if (maxBytes == 0L && wrapped.available() <= 0) return -1
            val maxToRead = if (maxBytes > 0) minOf(len.toLong(), maxBytes - bytesRead).toInt() else len
            if (maxToRead <= 0) return -1
            val count = wrapped.read(b, off, maxToRead)
            if (count != -1) bytesRead += count
            return count
        }

        override fun available(): Int {
            val avail = wrapped.available()
            return if (maxBytes > 0) minOf(avail.toLong(), maxBytes - bytesRead).toInt() else avail
        }

        override fun close() {
            // Do not close underlying socket stream
        }
    }
}
