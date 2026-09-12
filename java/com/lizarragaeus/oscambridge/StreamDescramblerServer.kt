package com.lizarragaeus.oscambridge

import android.util.Log
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLDecoder

/**
 * Local HTTP Stream Descrambler proxy for Android TV.
 * Allows playing encrypted MPEG-TS recordings (.ts files), SAT>IP network streams,
 * or external IPTV channels using OSCam without requiring the TV's native tuner.
 *
 * Usage with any Android video player (VLC, ExoPlayer, Kodi, Nova Player):
 *   Open URL: http://127.0.0.1:9191/play?url=http://satip-server/stream?freq=11000...
 *   Or file:  http://127.0.0.1:9191/play?file=/sdcard/recordings/channel.ts
 *
 * Log Tag: OscamCasBridge
 */
class StreamDescramblerServer(private val port: Int = 9191) {

    companion object {
        private const val TAG = "OscamCasBridge"
        private const val TS_PACKET_SIZE = 188
        private const val BUFFER_PACKETS = 348 // 348 * 188 = ~65 KB buffer
    }

    private var server: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        try {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/play", StreamPlayHandler())
                createContext("/status", StreamStatusHandler())
                executor = null
                start()
            }
            Log.i(TAG, "StreamDescramblerServer started on http://127.0.0.1:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StreamDescramblerServer: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            server?.stop(0)
            server = null
            Log.i(TAG, "StreamDescramblerServer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping StreamDescramblerServer: ${e.message}", e)
        }
    }

    private inner class StreamStatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val response = "{\"status\":\"active\",\"port\":$port}"
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
    }

    private inner class StreamPlayHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                val query = exchange.requestURI.query ?: ""
                val params = parseQuery(query)

                val targetUrl = params["url"]
                val targetFile = params["file"]

                Log.i(TAG, "StreamDescrambler: Play request for url='$targetUrl', file='$targetFile'")

                var inputStream: InputStream? = null
                var connection: HttpURLConnection? = null

                try {
                    if (!targetUrl.isNullOrEmpty()) {
                        val url = URL(targetUrl)
                        connection = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 10000
                            requestMethod = "GET"
                        }
                        inputStream = connection.inputStream
                    } else if (!targetFile.isNullOrEmpty()) {
                        val file = File(targetFile)
                        if (!file.exists() || !file.canRead()) {
                            sendHttpError(exchange, 404, "File not found or unreadable: $targetFile")
                            return@launch
                        }
                        inputStream = FileInputStream(file)
                    } else {
                        sendHttpError(exchange, 400, "Missing 'url' or 'file' query parameter")
                        return@launch
                    }

                    exchange.responseHeaders.set("Content-Type", "video/mp2t")
                    exchange.responseHeaders.set("Accept-Ranges", "none")
                    exchange.sendResponseHeaders(200, 0) // Chunked streaming

                    val outputStream: OutputStream = exchange.responseBody
                    val buffer = ByteArray(BUFFER_PACKETS * TS_PACKET_SIZE)

                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (bytesRead > 0) {
                            // Ensure complete packets
                            val validBytes = (bytesRead / TS_PACKET_SIZE) * TS_PACKET_SIZE
                            if (validBytes > 0) {
                                OscamNativeBridge.nativeDescrambleBuffer(buffer, 0, validBytes)
                            }
                            outputStream.write(buffer, 0, bytesRead)
                            outputStream.flush()
                        }
                    }

                    outputStream.close()
                } catch (e: Exception) {
                    Log.w(TAG, "StreamDescrambler: Streaming ended: ${e.message}")
                } finally {
                    try {
                        inputStream?.close()
                        connection?.disconnect()
                    } catch (e: Exception) {
                        Log.d(TAG, "Error closing stream: ${e.message}")
                    }
                }
            }
        }
    }

    private fun sendHttpError(exchange: HttpExchange, code: Int, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.close()
    }

    private fun parseQuery(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val parts = pair.split("=")
            if (parts.size == 2) {
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = URLDecoder.decode(parts[1], "UTF-8")
                result[key] = value
            }
        }
        return result
    }
}


