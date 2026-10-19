package dev.popcorn.tv

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

// A loopback HTTP relay between libmpv and the Popcorn server.
//
// mpv's own network stack (ffmpeg's https with mbedtls) took ~90 seconds to
// get a request through from a remote Shield — Caddy logged it arriving long
// after the viewer had given up — while ExoPlayer on the same box, using
// Android's networking, connected at once. So mpv no longer connects out:
// it asks this relay on 127.0.0.1 over plain HTTP, and the relay fetches
// through HttpURLConnection (Android's stack, its DNS, TLS and connection
// pool), passing Range requests and partial responses through untouched.
// Paths map one to one, so HLS playlists' host-relative segment URLs land
// here as well.
class MpvRelay(origin: String, private val authToken: String) {
    private val origin = origin.trimEnd('/')
    // Explicitly IPv4: on Android getLoopbackAddress() is ::1, so a relay
    // bound there refused mpv's connections to 127.0.0.1.
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "mpv-relay").apply { isDaemon = true } }
    private val open = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var closed = false

    val localOrigin: String = "http://127.0.0.1:${server.localPort}"

    init {
        pool.execute {
            while (!closed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                open.add(client)
                pool.execute { serve(client) }
            }
        }
    }

    // https://tv.example/api/… -> http://127.0.0.1:port/api/… (other URLs unchanged).
    fun rewrite(url: String): String = if (url.startsWith(origin)) localOrigin + url.removePrefix(origin) else url

    fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        var upstream: HttpURLConnection? = null
        try {
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
                respondError(client.getOutputStream(), 405, "Method Not Allowed")
                return
            }
            val connection = (URL(origin + parts[1]).openConnection() as HttpURLConnection).apply {
                requestMethod = parts[0]
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "Cinnamon-mpv-relay")
                setRequestProperty("Accept-Encoding", "identity")
                if (authToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $authToken")
                headers["range"]?.let { setRequestProperty("Range", it) }
            }
            upstream = connection
            val code = connection.responseCode
            val out = client.getOutputStream()
            val head = StringBuilder("HTTP/1.1 $code ${connection.responseMessage ?: ""}\r\n")
            for (name in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) {
                connection.getHeaderField(name)?.let { head.append(name).append(": ").append(it).append("\r\n") }
            }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (parts[0] == "GET") {
                val body: InputStream? = if (code >= 400) connection.errorStream else connection.inputStream
                body?.use { copy(it, out) }
            }
            out.flush()
        } catch (e: Exception) {
            // mpv closes connections whenever it seeks; that is not an error.
            if (!closed) Log.d("mpv-relay", "relay connection ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            upstream?.disconnect()
            open.remove(client)
            runCatching { client.close() }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (!closed) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
        }
    }

    private fun respondError(out: OutputStream, code: Int, message: String) {
        out.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
            if (line.length > 16_384) return null
        }
    }
}
