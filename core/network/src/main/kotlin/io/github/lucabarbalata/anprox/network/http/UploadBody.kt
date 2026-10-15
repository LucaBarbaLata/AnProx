package io.github.lucabarbalata.anprox.network.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.InputStream

/** A file to upload. It is opened again each time the request is (re)sent. */
interface UploadSource {
    val name: String

    /** Bytes, or -1 when unknown. */
    val size: Long

    fun open(): InputStream
}

/** Streams an [UploadSource] without holding it in memory and reports the bytes sent so far. */
class UploadBody(private val source: UploadSource, private val onProgress: (sent: Long) -> Unit) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = source.size

    override fun writeTo(sink: BufferedSink) {
        source.open().source().use { input ->
            val buffer = Buffer()
            var sent = 0L
            while (true) {
                val read = input.read(buffer, CHUNK)
                if (read == -1L) break
                sink.write(buffer, read)
                sent += read
                onProgress(sent)
            }
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024L
    }
}
