package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The `.vv` file a SPICE viewer (virt-viewer, aSPICE…) opens, built from the answer of
 * `POST /nodes/{node}/qemu/{vmid}/spiceproxy` (questionnaire Q15). It holds a one-time password
 * that expires after about 30 seconds, so it is made right before the viewer opens it.
 */
object SpiceFile {
    const val MIME_TYPE = "application/x-virt-viewer"

    /** `[virt-viewer]` and one `key=value` per field; newlines (in the CA certificate) as `\n`. */
    fun render(config: JsonObject): String = buildString {
        append("[virt-viewer]\n")
        config.forEach { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
            append(key).append('=').append(text.replace("\r", "").replace("\n", "\\n")).append('\n')
        }
    }

    /** Proxmox's SPICE proxy listens on port 3128 of every node; the app asks for the address it already uses. */
    fun proxyParams(host: String): Map<String, String> = mapOf("proxy" to host)
}
