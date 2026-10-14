package io.github.lucabarbalata.anprox.model.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Proxmox encodes booleans inconsistently: `1`/`0`, `true`/`false`, and sometimes `"1"`.
 * Annotate a `Boolean` property with `@Serializable(with = PveBooleanSerializer::class)`
 * to accept all of them. Encodes as `1`/`0`, which every Proxmox endpoint accepts.
 */
object PveBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("anprox.PveBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) return element.toPveBoolean()
            return false
        }
        return decoder.decodeBoolean()
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeInt(if (value) 1 else 0)
    }
}

/** Interprets a JSON primitive the way Proxmox means it. */
fun JsonPrimitive.toPveBoolean(): Boolean {
    booleanOrNull?.let { return it }
    doubleOrNull?.let { return it != 0.0 }
    return when (content.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }
}
