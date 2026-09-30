package com.minehost.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the fix for the "players ping forever" tunnel bug: the splice must
 * carry bytes in BOTH directions, and it must not lose the bytes the buffered
 * reader already pulled ahead of the handshake read.
 */
class TunnelSpliceTest {

    @Test
    fun `player bytes reach the local server and its reply reaches the player`() = runBlocking {
        // Fake game server: echoes everything back, like a status reply does.
        val game = ServerSocket(0)
        val echo = thread(isDaemon = true) {
            runCatching {
                game.accept().use { server ->
                    val buf = ByteArray(1024)
                    while (true) {
                        val n = server.getInputStream().read(buf)
                        if (n < 0) break
                        server.getOutputStream().write(buf, 0, n)
                        server.getOutputStream().flush()
                    }
                }
            }
        }

        // Player side as a real socket pair so the streams behave like the tunnel's.
        val tunnelListener = ServerSocket(0)
        val player = Socket("127.0.0.1", tunnelListener.localPort).apply { soTimeout = 5_000 }
        val tunnelSide = tunnelListener.accept()

        val local = Socket("127.0.0.1", game.localPort)
        try {
            // Must be dispatched off the runBlocking event loop: the test thread
            // then blocks in a plain socket read, which never yields back.
            val splice = async(Dispatchers.IO) {
                // Buffered reader deliberately created first: read-ahead here is
                // exactly what used to be dropped.
                val buffered = BufferedInputStream(tunnelSide.getInputStream())
                splicePlayerConnection(tunnelSide, buffered, tunnelSide.getOutputStream(), local, 1024)
            }

            player.getOutputStream().write("hello minecraft".toByteArray())
            player.getOutputStream().flush()

            val reply = ByteArray(64)
            val read = player.getInputStream().read(reply)
            assertEquals("hello minecraft", String(reply, 0, read))

            player.close()
            splice.join()
        } finally {
            runCatching { player.close() }
            runCatching { tunnelSide.close() }
            runCatching { local.close() }
            runCatching { tunnelListener.close() }
            runCatching { game.close() }
            echo.join(2_000)
        }
    }
}
