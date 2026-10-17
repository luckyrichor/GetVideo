package com.getvideo.app.data.repo

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class ServerProbeTest {
    @Test fun `health response completes and validates content`() = runBlocking {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = "{\"ok\":true,\"app\":\"GetVideo\"}"
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
            }
            probeServerHealth("http://127.0.0.1:${server.localPort}/", OkHttpClient())
            worker.join(1000)
        }
    }

    @Test fun `healthy GetAudio endpoint is rejected as the wrong product`() = runBlocking {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = "{\"ok\":true,\"app\":\"GetAudio\"}"
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
            }
            try {
                probeServerHealth("http://127.0.0.1:${server.localPort}/", OkHttpClient())
                fail("Must not accept another product as GetVideo")
            } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("GetVideo")) }
            worker.join(1000)
        }
    }

    @Test fun `unresponsive server is cancelled within the probe deadline`() = runBlocking {
        ServerSocket(0).use { server ->
            val client = OkHttpClient()
            val started = System.nanoTime()
            try {
                probeServerHealth("http://127.0.0.1:${server.localPort}/", client, 200)
                fail("Expected deadline")
            } catch (_: TimeoutCancellationException) { }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
            withTimeout(2000) { while (client.dispatcher.runningCallsCount() > 0) delay(10) }
        }
    }
}
