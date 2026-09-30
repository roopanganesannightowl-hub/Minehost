package com.minehost.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Bridges one forwarded player connection in **both** directions until either
 * side hangs up.
 *
 * Two rules matter here:
 *
 *  1. [playerIn] must be the one buffered reader already used to consume the
 *     tunnel's handshake bytes. Re-wrapping `socket.getInputStream()` later
 *     would silently drop whatever the first wrapper read ahead — which is the
 *     head of the player's Minecraft handshake.
 *  2. [playerOut] must be the relay's **raw** output stream, not a buffered
 *     one. A buffered stream would hold small responses (the status JSON, chat
 *     packets) until its buffer fills, so clients sit on "pinging" forever.
 *
 * When either pump finishes, both sockets are closed so the other pump
 * unblocks instead of hanging a coroutine and leaking the connection.
 */
internal suspend fun splicePlayerConnection(
    playerSocket: Socket,
    playerIn: InputStream,
    playerOut: OutputStream,
    local: Socket,
    bufferSize: Int,
) = coroutineScope {
    val closeBoth = {
        runCatching { local.close() }
        runCatching { playerSocket.close() }
    }
    val playerToServer = launch(Dispatchers.IO) {
        runCatching { playerIn.copyTo(local.getOutputStream(), bufferSize) }
        closeBoth()
    }
    val serverToPlayer = launch(Dispatchers.IO) {
        runCatching { local.getInputStream().copyTo(playerOut, bufferSize) }
        closeBoth()
    }
    playerToServer.join()
    serverToPlayer.join()
}
