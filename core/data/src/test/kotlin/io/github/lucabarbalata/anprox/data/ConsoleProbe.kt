package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.console.ConsoleEvent
import io.github.lucabarbalata.anprox.data.console.ConsoleSession
import io.github.lucabarbalata.anprox.mock.vncAuthResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/**
 * Plays the part of the app's bundled pages on a console the app opened: xterm.js for terminals
 * ([TerminalReader]) and noVNC for a VM's screen ([readVncScreen]). The real-server tests use it
 * to check consoles end to end, through the app's own connection.
 */
internal suspend fun ConsoleSession.awaitConnected() = withTimeout(20_000) {
    when (val event = events.first { it !is ConsoleEvent.Output }) {
        ConsoleEvent.Connected -> Unit
        is ConsoleEvent.Failed -> error("the console failed: ${event.message}")
        is ConsoleEvent.Closed -> error("the console closed: ${event.reason}")
        is ConsoleEvent.Output -> Unit
    }
}

/** A terminal's output as text; each [await] looks at what arrived after the previous match. */
internal class TerminalReader(private val session: ConsoleSession) {
    private val seen = StringBuilder()
    private var mark = 0

    /** Waits for the first of [texts]; returns it with the output up to it, or null after [millis]. */
    suspend fun await(vararg texts: String, millis: Long = 15_000): Pair<String, String>? = withTimeoutOrNull(millis) {
        var hit: Pair<String, String>? = null
        while (hit == null) {
            val found = texts.mapNotNull { t -> seen.indexOf(t, mark).takeIf { it >= 0 }?.let { it to t } }.minByOrNull { it.first }
            if (found != null) {
                val end = found.first + found.second.length
                hit = found.second to seen.substring(mark, end)
                mark = end
            } else {
                when (val event = session.events.first()) {
                    is ConsoleEvent.Output -> seen.append(String(event.bytes, Charsets.UTF_8))
                    is ConsoleEvent.Failed -> error("the console failed: ${event.message}")
                    is ConsoleEvent.Closed -> error("the console closed: ${event.reason}")
                    ConsoleEvent.Connected -> Unit
                }
            }
        }
        hit
    }

    /** Like [await] for one text, but fails when it doesn't come. */
    suspend fun expect(text: String, millis: Long = 15_000): String =
        await(text, millis = millis)?.second ?: error("the terminal didn't show \"$text\" within ${millis / 1000} s")

    companion object {
        /** `stty size` prints "rows cols" on a line of its own. */
        private val STTY_SIZE = Regex("""(?:^|[\r\n])(\d+) (\d+)\r?\n""")

        fun sttySize(output: String): Pair<Int, Int>? =
            STTY_SIZE.find(output)?.destructured?.let { (rows, cols) -> cols.toInt() to rows.toInt() }

        /** Terminal output with control characters spelled out (`\r`, `\n`, `\e`, `\xNN`), for a log line. */
        fun visible(output: String): String = buildString {
            output.forEach { c ->
                when {
                    c == '\r' -> append("\\r")
                    c == '\n' -> append("\\n")
                    c == '\u001b' -> append("\\e")
                    c < ' ' || c == '\u007f' -> append("\\x%02x".format(c.code))
                    else -> append(c)
                }
            }
        }
    }
}

/** A VM's screen as one full frame, in 0xRRGGBB pixels. */
internal class VncScreen(val name: String, val width: Int, val height: Int, val pixels: IntArray) {
    val colours: Int get() = pixels.distinct().size

    fun png(): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}

/**
 * What noVNC does on a VM console: RFB 3.8 with VNC authentication (the password the app got
 * from `vncproxy`), 32-bit pixels in raw encoding, size changes, and a full frame. A VM that has
 * nothing to boot resets every second, so a frame that comes back blank is read again. Then a key
 * press (Shift, which changes nothing) and another frame: input goes through and the session
 * keeps answering.
 */
internal suspend fun readVncScreen(session: ConsoleSession): VncScreen {
    val feed = RfbFeed(session)
    val version = String(feed.read(12))
    check(version.startsWith("RFB 003.")) { "not a VNC server: ${version.trim()}" }
    session.sendBinary("RFB 003.008\n".toByteArray())
    val count = feed.u8()
    if (count == 0) error("the VNC server refused the connection: ${String(feed.read(feed.s32()))}")
    val types = List(count) { feed.u8() }
    when {
        VNC_AUTH in types -> {
            session.sendBinary(byteArrayOf(VNC_AUTH.toByte()))
            session.sendBinary(vncAuthResponse(checkNotNull(session.vncPassword) { "no VNC password from vncproxy" }, feed.read(16)))
        }
        NO_AUTH in types -> session.sendBinary(byteArrayOf(NO_AUTH.toByte()))
        else -> error("the VNC server offers no security type noVNC uses here: $types")
    }
    if (feed.s32() != 0) error("VNC authentication failed")
    session.sendBinary(byteArrayOf(1)) // ClientInit: share the screen
    val width = feed.u16()
    val height = feed.u16()
    feed.read(16) // the server's pixel format
    feed.name = String(feed.read(feed.s32()))
    feed.resize(width, height)
    // SetPixelFormat as noVNC asks: 32 bpp, depth 24, little endian, true colour, red at bit 0.
    session.sendBinary(
        ByteBuffer.allocate(20).put(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1))
            .putShort(255).putShort(255).putShort(255).put(byteArrayOf(0, 8, 16, 0, 0, 0)).array(),
    )
    session.sendBinary(ByteBuffer.allocate(12).put(2).put(0).putShort(2).putInt(RAW).putInt(DESKTOP_SIZE).array()) // SetEncodings
    var screen = feed.frame()
    for (attempt in 1..5) {
        if (screen.colours > 1) break
        delay(1_000)
        screen = feed.frame()
    }
    session.sendBinary(ByteBuffer.allocate(8).put(4).put(1).putShort(0).putInt(SHIFT).array())
    session.sendBinary(ByteBuffer.allocate(8).put(4).put(0).putShort(0).putInt(SHIFT).array())
    feed.frame()
    return screen
}

private const val NO_AUTH = 1
private const val VNC_AUTH = 2
private const val RAW = 0
private const val DESKTOP_SIZE = -223
private const val SHIFT = 0xffe1

/**
 * The VM console's bytes as a stream (RFB messages don't follow WebSocket message boundaries),
 * and the screen they paint.
 */
private class RfbFeed(private val session: ConsoleSession) {
    private var buffer = ByteArray(0)
    private var start = 0
    var name = ""
    private var width = 0
    private var height = 0
    private var pixels = IntArray(0)

    fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
        pixels = IntArray(width * height)
    }

    suspend fun read(count: Int): ByteArray = withTimeout(30_000) {
        while (buffer.size - start < count) {
            val more = when (val event = session.events.first { it !is ConsoleEvent.Connected }) {
                is ConsoleEvent.Output -> event.bytes
                is ConsoleEvent.Failed -> error("the console failed: ${event.message}")
                is ConsoleEvent.Closed -> error("the console closed: ${event.reason}")
                ConsoleEvent.Connected -> ByteArray(0)
            }
            buffer = buffer.copyOfRange(start, buffer.size) + more
            start = 0
        }
        buffer.copyOfRange(start, start + count).also { start += count }
    }

    suspend fun u8(): Int = read(1)[0].toInt() and 0xff

    suspend fun u16(): Int = ByteBuffer.wrap(read(2)).short.toInt() and 0xffff

    suspend fun s32(): Int = ByteBuffer.wrap(read(4)).int

    /** Asks for the whole screen until an update paints it without changing its size. */
    suspend fun frame(): VncScreen {
        for (attempt in 1..3) {
            session.sendBinary(ByteBuffer.allocate(10).put(3).put(0).putShort(0).putShort(0).putShort(width.toShort()).putShort(height.toShort()).array())
            if (!update()) break
        }
        return VncScreen(name, width, height, pixels.copyOf())
    }

    /** Reads up to and including one FramebufferUpdate; true when it changed the screen size. */
    private suspend fun update(): Boolean {
        while (true) {
            when (val type = u8()) {
                0 -> {
                    read(1)
                    var resized = false
                    repeat(u16()) {
                        val x = u16()
                        val y = u16()
                        val w = u16()
                        val h = u16()
                        when (val encoding = s32()) {
                            DESKTOP_SIZE -> resize(w, h).also { resized = true }
                            RAW -> paint(x, y, w, h, read(w * h * 4))
                            else -> error("the server sent encoding $encoding, which wasn't asked for")
                        }
                    }
                    return resized
                }
                1 -> { // SetColourMapEntries
                    read(3)
                    read(u16() * 6)
                }
                2 -> Unit // Bell
                3 -> { // ServerCutText
                    read(3)
                    read(s32())
                }
                else -> error("unexpected VNC message type $type")
            }
        }
    }

    private fun paint(x: Int, y: Int, w: Int, h: Int, data: ByteArray) {
        check(x + w <= width && y + h <= height) { "a rectangle outside the ${width}x$height screen" }
        for (row in 0 until h) {
            for (col in 0 until w) {
                val i = (row * w + col) * 4
                pixels[(y + row) * width + x + col] =
                    (data[i].toInt() and 0xff shl 16) or (data[i + 1].toInt() and 0xff shl 8) or (data[i + 2].toInt() and 0xff)
            }
        }
    }
}
