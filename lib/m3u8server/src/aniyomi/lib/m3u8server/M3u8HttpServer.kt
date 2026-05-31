package aniyomi.lib.m3u8server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newChunkedResponse
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.IOException
import java.net.URLEncoder

/**
 * Real HTTP server for M3U8 processing using NanoHTTPD
 * Compatible with Android and provides actual HTTP endpoints
 */
class M3u8HttpServer(
    private val client: OkHttpClient,
    port: Int = 0, // 0 means random port
) : NanoHTTPD(port) {

    val port: Int
        get() = super.getListeningPort()

    private val tag by lazy { javaClass.simpleName }

    @Volatile
    private var isRunning = false

    override fun start() {
        try {
            super.start()
            isRunning = true
            Log.d(tag, "M3U8 HTTP Server started on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}")
            throw e
        }
    }

    override fun stop() {
        super.stop()
        isRunning = false
        Log.d(tag, "M3U8 HTTP Server stopped")
    }

    fun isRunning(): Boolean = isRunning

    override fun handle(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(tag, "Received request: $method $uri from ${session.remoteIpAddress}")

        val response = when {
            uri.startsWith("/m3u8") -> handleM3u8Request(session)
            uri.startsWith("/segment") -> handleSegmentRequest(session)
            uri.startsWith("/health") -> handleHealthRequest()
            else -> {
                Log.w(tag, "Unknown endpoint: $uri")
                newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        Log.d(tag, "Response status: ${response.status}")
        return response
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
        val headers = extractHeadersFromSession(session)

        Log.d(tag, "Processing M3U8 request for URL: $url")
        Log.d(tag, "Headers: $headers")

        if (url.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in M3U8 request")
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        return try {
            Log.d(tag, "Starting M3U8 processing for: $url")
            val processedContent = runBlocking { processM3u8Content(url, headers) }
            Log.d(tag, "M3U8 processing completed successfully, content length: ${processedContent.length}")
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", processedContent)
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
        val headers = extractHeadersFromSession(session)
        return handleSegment(url, headers)
    }

    fun handleSegment(url: String?, headers: Map<String, String> = emptyMap()): Response {
        Log.d(tag, "Processing segment request for URL: $url")

        if (url.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in segment request")
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        return try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }
            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("Failed to fetch segment: ${response.code}")
            }
            val inputStream = AutoDetectingInputStream(response, response.body.byteStream())
            newChunkedResponse(Status.OK, "video/mp2t", inputStream)
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private class AutoDetectingInputStream(
        private val response: okhttp3.Response,
        private val delegate: java.io.InputStream
    ) : java.io.InputStream() {
        private var detected = false
        private var prefixBuffer: ByteArray? = null
        private var prefixPointer = 0
        private var prefixLength = 0

        private fun ensureDetected() {
            if (detected) return
            detected = true
            val buffer = ByteArray(4096)
            var bytesRead = 0
            while (bytesRead < buffer.size) {
                val read = delegate.read(buffer, bytesRead, buffer.size - bytesRead)
                if (read == -1) break
                bytesRead += read
            }
            if (bytesRead > 0) {
                val skipBytes = AutoDetector.detectSkipBytes(buffer.copyOf(bytesRead))
                prefixBuffer = buffer
                prefixPointer = skipBytes
                prefixLength = bytesRead
            }
        }

        override fun read(): Int {
            ensureDetected()
            val buf = prefixBuffer
            if (buf != null && prefixPointer < prefixLength) {
                val b = buf[prefixPointer].toInt() and 0xFF
                prefixPointer++
                if (prefixPointer >= prefixLength) {
                    prefixBuffer = null
                }
                return b
            }
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            ensureDetected()
            val buf = prefixBuffer
            if (buf != null && prefixPointer < prefixLength) {
                val available = prefixLength - prefixPointer
                val toCopy = minOf(len, available)
                System.arraycopy(buf, prefixPointer, b, off, toCopy)
                prefixPointer += toCopy
                if (prefixPointer >= prefixLength) {
                    prefixBuffer = null
                }
                return toCopy
            }
            return delegate.read(b, off, len)
        }

        override fun close() {
            delegate.close()
            response.close()
        }
    }

    private fun handleHealthRequest(): Response {
        Log.d(tag, "Health check requested")
        val status = getHealthStatus()
        Log.d(tag, "Health status: $status")
        return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, status)
    }

    /**
     * Extract headers from the HTTP session
     */
    private fun extractHeadersFromSession(session: IHTTPSession): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        // Extract common headers that might be needed for video requests
        session.headers.forEach { (key, value) ->
            when (key.lowercase()) {
                "user-agent", "referer", "origin", "accept", "accept-language",
                "accept-encoding", "connection", "cache-control", "pragma",
                -> {
                    headers[key] = value
                }
            }
        }

        Log.d(tag, "Extracted headers: $headers")
        return headers
    }

    /**
     * Process M3U8 content through the server
     */
    private suspend fun processM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Fetching M3U8 content from: $url with headers: $headers")
            val m3u8Content = fetchM3u8Content(url, headers)
            Log.d(tag, "Original M3U8 content length: ${m3u8Content.length}")

            val modifiedContent = modifyM3u8Content(m3u8Content, url, port)
            Log.d(tag, "Modified M3U8 content length: ${modifiedContent.length}")
            Log.d(tag, "M3U8 processing completed successfully")

            modifiedContent
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8 URL: ${e.message}", e)
            throw IOException("Error processing m3u8: ${e.message}")
        }
    }

    /**
     * Health check
     */
    fun getHealthStatus(): String = if (isRunning) {
        "M3U8 HTTP Server is running on port $port"
    } else {
        "M3U8 HTTP Server is not running"
    }

    private suspend fun fetchM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        Log.d(tag, "Making HTTP request to fetch M3U8 content with headers: $headers")

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            Log.d(tag, "M3U8 HTTP response code: ${response.code}")

            if (!response.isSuccessful) {
                Log.e(tag, "Failed to fetch M3U8 content, HTTP code: ${response.code}")
                throw IOException("Failed to fetch m3u8: ${response.code}")
            }

            val content = response.body.string()
            if (content.isBlank()) {
                Log.e(tag, "Empty M3U8 response body")
                throw IOException("Empty response body")
            }

            Log.d(tag, "Successfully fetched M3U8 content")
            content
        }
    }

    /**
     * Creates a local M3U8 URL by encoding the original URL and redirecting to the local server.
     * It can either be segment URL or a direct M3U8 URL (not a playlist).
     */
    fun createLocalUrl(m3u8Url: String): String {
        val encodedUrl = URLEncoder.encode(m3u8Url, Charsets.UTF_8.name())
        return "http://localhost:$port/m3u8?url=$encodedUrl"
    }

    private fun modifyM3u8Content(content: String, originalUrl: String, serverPort: Int): String {
        Log.d(tag, "Modifying M3U8 content for server port: $serverPort")
        val lines = content.lines().toMutableList()
        val modifiedLines = mutableListOf<String>()
        var segmentCount = 0

        // Determine base URL from the original URL
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()

        for (line in lines) {
            when {
                line.startsWith("#") -> {
                    // Keep comments and headers
                    modifiedLines.add(line)
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    // This is a segment URL, resolve against base URL
                    val resolvedUrl = baseHttpUrl?.resolve(line)?.toString() ?: line
                    val encodedUrl = URLEncoder.encode(resolvedUrl, Charsets.UTF_8.name())
                    val localUrl = "http://localhost:$serverPort/segment?url=$encodedUrl"
                    modifiedLines.add(localUrl)
                    segmentCount++
                }
                else -> {
                    // Keep empty lines
                    modifiedLines.add(line)
                }
            }
        }

        Log.d(tag, "Modified M3U8 content: $segmentCount segments redirected to local server")
        return modifiedLines.joinToString("\n")
    }
}
