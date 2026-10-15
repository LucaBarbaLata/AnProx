package io.github.lucabarbalata.anprox.mock

import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** A console ticket handed out by `termproxy` / `vncproxy`, valid for one WebSocket. */
data class MockConsoleTicket(
    val ticket: String,
    val port: String,
    val user: String,
    /** The API path of the WebSocket it opens. */
    val websocketPath: String,
    /** Host name shown in the terminal prompt, or the VM name as the VNC desktop name. */
    val name: String,
    /** VNC only: the password the client must answer the challenge with. */
    val vncPassword: String? = null,
)

/**
 * A terminal like Proxmox's termproxy: `user:ticket\n` first, then `OK` and the output.
 * Input (`0:len:data`) is echoed; Enter starts a new prompt, Esc and Ctrl+C show as `^[`/`^C`.
 */
internal suspend fun DefaultWebSocketSession.mockTerminal(console: MockConsoleTicket) {
    val prompt = "root@${console.name}:~# "
    try {
        val first = incoming.receive().text()
        if (first != "${console.user}:${console.ticket}\n") {
            send(Frame.Binary(true, "NO".toByteArray()))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid ticket"))
            return
        }
        send(Frame.Binary(true, "OK".toByteArray() + "Welcome to the AnProx mock shell on ${console.name}\r\n$prompt".toByteArray()))
        for (frame in incoming) {
            val message = frame.text()
            when {
                message == "2" -> Unit // keep-alive
                message.startsWith("1:") -> Unit // resize
                message.startsWith("0:") -> {
                    val data = message.substringAfter(':').substringAfter(':')
                    val echo = buildString {
                        data.forEach { c ->
                            when (c) {
                                '\r' -> append("\r\n$prompt")
                                '\u001b' -> append("^[")
                                '\u0003' -> append("^C\r\n$prompt")
                                '\u0004' -> append("logout\r\n")
                                else -> append(c)
                            }
                        }
                    }
                    send(Frame.Binary(true, echo.toByteArray()))
                }
            }
        }
    } catch (_: ClosedReceiveChannelException) {
        // client went away
    }
}

/**
 * Just enough of a VNC server for noVNC: RFB 3.8 with VNC authentication (the challenge is
 * checked against the password, as QEMU does), a desktop called [MockConsoleTicket.name] and one
 * plain frame in raw encoding.
 */
internal suspend fun DefaultWebSocketSession.mockVnc(console: MockConsoleTicket, width: Int = 320, height: Int = 200) {
    val reader = FrameReader(this)
    suspend fun out(bytes: ByteArray) = send(Frame.Binary(true, bytes))
    try {
        out("RFB 003.008\n".toByteArray())
        reader.read(12) // client version
        out(byteArrayOf(1, 2)) // one security type: VNC authentication
        reader.read(1)
        val challenge = ByteArray(16).also(SecureRandom()::nextBytes)
        out(challenge)
        val response = reader.read(16)
        if (!response.contentEquals(vncAuthResponse(console.vncPassword.orEmpty(), challenge))) {
            val reason = "Authentication failed".toByteArray()
            out(ByteBuffer.allocate(8 + reason.size).putInt(1).putInt(reason.size).put(reason).array())
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "authentication failed"))
            return
        }
        out(ByteBuffer.allocate(4).putInt(0).array())
        reader.read(1) // ClientInit (shared flag)
        val name = console.name.toByteArray()
        out(
            ByteBuffer.allocate(24 + name.size)
                .putShort(width.toShort()).putShort(height.toShort())
                // pixel format: 32 bpp, depth 24, little endian, true colour, 8 bits per channel
                .put(byteArrayOf(32, 24, 0, 1)).putShort(255).putShort(255).putShort(255)
                .put(byteArrayOf(16, 8, 0, 0, 0, 0))
                .putInt(name.size).put(name)
                .array(),
        )
        var bytesPerPixel = 4
        while (true) {
            when (reader.read(1)[0].toInt()) {
                0 -> bytesPerPixel = (reader.read(19)[3].toInt() and 0xff) / 8 // SetPixelFormat: 3 padding + 16
                2 -> { // SetEncodings
                    val count = ByteBuffer.wrap(reader.read(3), 1, 2).short.toInt() and 0xffff
                    reader.read(4 * count)
                }
                3 -> { // FramebufferUpdateRequest
                    val incremental = reader.read(9)[0].toInt() != 0
                    if (!incremental) out(rawFrame(width, height, bytesPerPixel))
                }
                4 -> reader.read(7) // KeyEvent
                5 -> reader.read(5) // PointerEvent
                6 -> reader.read(ByteBuffer.wrap(reader.read(7), 3, 4).int) // ClientCutText
                else -> {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "unsupported message"))
                    return
                }
            }
        }
    } catch (_: ClosedReceiveChannelException) {
        // client went away
    }
}

/** VNC authentication: DES-encrypt the challenge with the password, each key byte bit-reversed. */
fun vncAuthResponse(password: String, challenge: ByteArray): ByteArray {
    val key = ByteArray(8)
    val bytes = password.toByteArray()
    bytes.copyInto(key, endIndex = minOf(8, bytes.size))
    for (i in key.indices) key[i] = (Integer.reverse(key[i].toInt() and 0xff) ushr 24).toByte()
    val cipher = Cipher.getInstance("DES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
    return cipher.doFinal(challenge)
}

/** A FramebufferUpdate with one rectangle filling the screen in the AnProx dark blue. */
private fun rawFrame(width: Int, height: Int, bytesPerPixel: Int): ByteArray {
    val header = ByteBuffer.allocate(16)
        .put(0).put(0).putShort(1)
        .putShort(0).putShort(0).putShort(width.toShort()).putShort(height.toShort())
        .putInt(0) // raw encoding
        .array()
    // noVNC asks for little-endian pixels with red at bit 0: bytes R, G, B, padding.
    val pixel = byteArrayOf(0x1e, 0x29, 0x3b, 0).copyOf(bytesPerPixel)
    val pixels = ByteArray(width * height * bytesPerPixel)
    for (i in pixels.indices step bytesPerPixel) pixel.copyInto(pixels, i)
    return header + pixels
}

/** Reads exact byte counts across WebSocket frames, like a stream. */
private class FrameReader(private val session: DefaultWebSocketSession) {
    private var buffer = ByteArray(0)

    suspend fun read(count: Int): ByteArray {
        while (buffer.size < count) buffer += session.incoming.receive().bytes()
        return buffer.copyOfRange(0, count).also { buffer = buffer.copyOfRange(count, buffer.size) }
    }
}

private fun Frame.bytes(): ByteArray = when (this) {
    is Frame.Binary -> readBytes()
    is Frame.Text -> readText().toByteArray()
    else -> ByteArray(0)
}

private fun Frame.text(): String = String(bytes())
