package io.github.lucabarbalata.anprox.model.pve

/**
 * A parsed Unique Process ID — the handle every long-running Proxmox operation returns.
 *
 * PVE: `UPID:{node}:{pid}:{pstart}:{starttime}:{type}:{id}:{user}:`
 * PBS: `UPID:{node}:{pid}:{pstart}:{taskId}:{starttime}:{type}:{id}:{user}:`
 * (pid, pstart, taskId and starttime are hexadecimal.)
 */
data class Upid(
    val raw: String,
    val node: String,
    val pid: Long,
    val startTimeEpochSeconds: Long,
    val type: String,
    val id: String,
    val user: String,
) {
    companion object {
        fun parse(raw: String): Upid? {
            val p = raw.split(':')
            if (p.firstOrNull() != "UPID") return null
            return runCatching {
                when (p.size) {
                    9 -> Upid(raw, p[1], p[2].toLong(16), p[4].toLong(16), p[5], unescape(p[6]), p[7])
                    10 -> Upid(raw, p[1], p[2].toLong(16), p[5].toLong(16), p[6], unescape(p[7]), p[8])
                    else -> null
                }
            }.getOrNull()
        }

        /** PBS escapes some characters in worker ids as `\xNN`. */
        private fun unescape(s: String): String =
            Regex("""\\x([0-9a-fA-F]{2})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
    }
}
