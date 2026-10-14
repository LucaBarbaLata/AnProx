package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /nodes/{node}/status` (PVE). */
@Serializable
data class NodeStatus(
    val cpu: Double = 0.0,
    /** IO wait fraction. */
    val wait: Double? = null,
    val loadavg: List<String> = emptyList(),
    val memory: MemoryUsage = MemoryUsage(),
    val swap: MemoryUsage? = null,
    val rootfs: FsUsage? = null,
    val uptime: Long = 0,
    val cpuinfo: CpuInfo? = null,
    val kversion: String? = null,
    val pveversion: String? = null,
    @SerialName("boot-info") val bootInfo: BootInfo? = null,
)

@Serializable
data class MemoryUsage(
    val total: Long = 0,
    val used: Long = 0,
    val free: Long = 0,
) {
    val fraction: Double? get() = fraction(used, total)
}

@Serializable
data class FsUsage(
    val total: Long = 0,
    val used: Long = 0,
    val free: Long? = null,
    val avail: Long? = null,
) {
    val fraction: Double? get() = fraction(used, total)
}

@Serializable
data class CpuInfo(
    val model: String? = null,
    val cpus: Int? = null,
    val sockets: Int? = null,
    val cores: Int? = null,
    val mhz: String? = null,
)

@Serializable
data class BootInfo(
    val mode: String? = null,
    val secureboot: Int? = null,
)
