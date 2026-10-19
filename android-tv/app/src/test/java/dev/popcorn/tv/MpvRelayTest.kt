package dev.popcorn.tv

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

class MpvRelayTest {
    private val body = ByteArray(1000) { (it % 251).toByte() }
    private lateinit var upstream: ServerSocket
    private lateinit var relay: MpvRelay
    @Volatile private var seenAuth: String? = null

    // A minimal range-capable file server standing in for popcornd.
    @Before
    fun start() {
        upstream = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!upstream.isClosed) {
                val socket = runCatching { upstream.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { client ->
                        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        reader.readLine()
                        var range: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val (name, value) = line.split(":", limit = 2).map { it.trim() }
                            if (name.equals("Range", true)) range = value
                            if (name.equals("Authorization", true)) seenAuth = value
                        }
                        val out = client.getOutputStream()
                        if (range != null) {
                            val (from, to) = range.removePrefix("bytes=").split("-").map { it.toInt() }
                            out.write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/${body.size}\r\nContent-Length: ${to - from + 1}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body, from, to - from + 1)
                        } else {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        }
                        out.flush()
                    }
                }
            }
        }
        relay = MpvRelay(origin(), "secret")
    }

    @After
    fun stop() {
        relay.close()
        upstream.close()
    }

    private fun origin() = "http://127.0.0.1:${upstream.localPort}"

    private fun get(url: String, range: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply { range?.let { setRequestProperty("Range", it) } }

    @Test
    fun rewritesOnlyTheServerOrigin() {
        assertEquals(relay.localOrigin + "/api/items/1/stream?x=1", relay.rewrite(origin() + "/api/items/1/stream?x=1"))
        assertEquals("https://elsewhere/x", relay.rewrite("https://elsewhere/x"))
    }

    @Test
    fun passesWholeFileAndAddsAuth() {
        val conn = get(relay.rewrite(origin() + "/api/items/1/stream"))
        assertEquals(200, conn.responseCode)
        assertArrayEquals(body, conn.inputStream.readBytes())
        assertEquals("Bearer secret", seenAuth)
    }

    @Test
    fun passesRangeRequestsThrough() {
        val conn = get(relay.rewrite(origin() + "/api/items/1/stream"), "bytes=100-199")
        assertEquals(206, conn.responseCode)
        assertEquals("bytes 100-199/1000", conn.getHeaderField("Content-Range"))
        assertArrayEquals(body.copyOfRange(100, 200), conn.inputStream.readBytes())
    }
}
