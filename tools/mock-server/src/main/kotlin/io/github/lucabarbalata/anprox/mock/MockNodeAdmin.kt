package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.AptRepositories
import io.github.lucabarbalata.anprox.model.pve.AptRepository
import io.github.lucabarbalata.anprox.model.pve.AptRepositoryFile
import io.github.lucabarbalata.anprox.model.pve.AptRepositoryInfo
import io.github.lucabarbalata.anprox.model.pve.AptUpdate
import io.github.lucabarbalata.anprox.model.pve.NodeDisk
import io.github.lucabarbalata.anprox.model.pve.NodeDns
import io.github.lucabarbalata.anprox.model.pve.SmartAttribute
import io.github.lucabarbalata.anprox.model.pve.SmartData
import io.github.lucabarbalata.anprox.model.pve.NodeHosts
import io.github.lucabarbalata.anprox.model.pve.NodeTime
import io.github.lucabarbalata.anprox.model.pve.NodeService
import io.github.lucabarbalata.anprox.model.pve.NodeSubscription
import io.github.lucabarbalata.anprox.model.pve.PackageVersion
import io.github.lucabarbalata.anprox.model.pve.StandardRepository

/**
 * Node administration (questionnaire Q22): upgradable packages, APT repositories as Proxmox VE 8
 * ships them (Debian, security, and the enterprise repository without a subscription, which
 * Proxmox warns about), services, and a journal.
 */
internal class MockNodeAdmin(nodes: List<String>) {
    private class Repo(val types: List<String>, val uri: String, val suite: String, val components: List<String>, var enabled: Boolean = true)

    private class NodeData(
        val updates: List<AptUpdate>,
        val files: LinkedHashMap<String, MutableList<Repo>>,
        val services: LinkedHashMap<String, String>,
        var version: Int = 1,
        var dns: NodeDns = NodeDns("home.lan", "10.0.0.1", "1.1.1.1"),
        var hosts: String = "",
        var hostsVersion: Int = 1,
        var timezone: String = "Europe/Bucharest",
        val disks: MutableList<NodeDisk> = mutableListOf(
            NodeDisk("/dev/nvme0n1", 1_000_204_886_016, "Samsung SSD 980 PRO 1TB", "S5GXNF0R123456", type = "nvme", health = "PASSED", wearout = kotlinx.serialization.json.JsonPrimitive(97), used = "LVM", gpt = true),
            NodeDisk("/dev/sda", 4_000_787_030_016, "WDC WD40EFRX-68N32N0", "WD-WCC7K1234567", type = "hdd", health = "PASSED", used = "ZFS", gpt = true, rpm = kotlinx.serialization.json.JsonPrimitive(5400)),
            NodeDisk("/dev/sdb", 1_000_204_886_016, "CT1000MX500SSD1", "2033E1234567", type = "ssd", health = "PASSED", wearout = kotlinx.serialization.json.JsonPrimitive(88)),
            NodeDisk("/dev/sdc", 2_000_398_934_016, "ST2000DM008-2FR102", "ZFL1ABCD", type = "hdd", health = "PASSED", gpt = true, rpm = kotlinx.serialization.json.JsonPrimitive(7200)),
        ),
    )

    private val data = nodes.associateWith { node ->
        NodeData(
            updates = if (node == "pve1") {
                listOf(
                    AptUpdate("pve-manager", "Proxmox Virtual Environment Management Tools", "8.4.1", "8.4.5", "Proxmox", "important", "admin"),
                    AptUpdate("proxmox-kernel-6.8", "Latest Proxmox Kernel Image", "6.8.12-9", "6.8.12-13", "Proxmox", "optional", "admin"),
                    AptUpdate("openssl", "Secure Sockets Layer toolkit", "3.0.15-1~deb12u1", "3.0.17-1~deb12u2", "Debian", "optional", "utils"),
                )
            } else {
                listOf(AptUpdate("openssl", "Secure Sockets Layer toolkit", "3.0.15-1~deb12u1", "3.0.17-1~deb12u2", "Debian", "optional", "utils"))
            },
            files = linkedMapOf(
                "/etc/apt/sources.list" to mutableListOf(
                    Repo(listOf("deb"), "http://deb.debian.org/debian", "bookworm", listOf("main", "contrib")),
                    Repo(listOf("deb"), "http://deb.debian.org/debian", "bookworm-updates", listOf("main", "contrib")),
                    Repo(listOf("deb"), "http://security.debian.org/debian-security", "bookworm-security", listOf("main", "contrib")),
                ),
                "/etc/apt/sources.list.d/pve-enterprise.list" to mutableListOf(
                    Repo(listOf("deb"), "https://enterprise.proxmox.com/debian/pve", "bookworm", listOf("pve-enterprise")),
                ),
            ),
            services = linkedMapOf(
                "pveproxy" to "running", "pvedaemon" to "running", "pvestatd" to "running", "pve-cluster" to "running",
                "corosync" to "running", "pve-firewall" to "running", "pve-ha-crm" to "running", "pve-ha-lrm" to "running",
                "spiceproxy" to "running", "chrony" to "running", "sshd" to "running", "cron" to "running", "postfix" to "running",
                "systemd-journald" to "running", "ksmtuned" to "stopped",
            ),
        )
    }

    private fun node(name: String) = data[name]

    fun updates(node: String): List<AptUpdate>? = node(node)?.updates

    fun changelog(node: String, name: String): String? = node(node)?.updates?.firstOrNull { it.name == name }?.let { u ->
        "$name (${u.version}) bookworm; urgency=medium\n\n  * Fix a few things found since ${u.oldVersion}.\n  * Small improvements.\n\n -- Proxmox Support Team <support@proxmox.com>  Wed, 15 Oct 2026 10:00:00 +0200\n"
    }

    fun versions(node: String): List<PackageVersion>? = node(node)?.let {
        listOf(
            PackageVersion("proxmox-ve", "8.4.0", state = "Installed"),
            PackageVersion("pve-manager", "8.4.1", state = "Installed"),
            PackageVersion("proxmox-kernel-6.8", "6.8.12-9", state = "Installed"),
            PackageVersion("qemu-server", "8.3.12", state = "Installed"),
            PackageVersion("lxc-pve", "6.0.0-1", state = "Installed"),
        )
    }

    private var subscriptionKey: String? = null

    fun subscription(): NodeSubscription = subscriptionKey?.let {
        NodeSubscription(status = "active", level = "c", product = "Proxmox VE Community Subscription 1 CPU/year", nextDueDate = "2027-10-15")
    } ?: NodeSubscription(status = "notfound", message = "There is no subscription key")

    fun setSubscriptionKey(key: String?): String? {
        if (key != null && !Regex("^pve([1248])([cbsp])-[0-9a-f]{10}$").matches(key)) return "key: invalid format - value does not look like a valid subscription key"
        subscriptionKey = key
        return null
    }

    fun repositories(node: String): AptRepositories? {
        val d = node(node) ?: return null
        val files = d.files.map { (path, repos) ->
            AptRepositoryFile(path, "list", repos.map { AptRepository(it.types, listOf(it.uri), listOf(it.suite), it.components, enabled = it.enabled) })
        }
        val infos = buildList {
            d.files.forEach { (path, repos) ->
                repos.forEachIndexed { i, r ->
                    val origin = if ("proxmox" in r.uri) "Proxmox" else "Debian"
                    add(AptRepositoryInfo(path, i, "origin", origin))
                    if (r.enabled && "pve-enterprise" in r.components) {
                        add(AptRepositoryInfo(path, i, "warning", "The enterprise repository is enabled, but there is no active subscription!"))
                    }
                }
            }
            val anyPve = d.files.values.flatten().any { it.enabled && "proxmox" in it.uri }
            if (!anyPve) add(AptRepositoryInfo("/etc/apt/sources.list", -1, "warning", "No Proxmox VE repository is enabled, you do not get any updates!"))
        }
        return AptRepositories(digest(d), files, emptyList(), infos, standard(d))
    }

    private fun standard(d: NodeData): List<StandardRepository> {
        fun status(component: String) = d.files.values.flatten().firstOrNull { component in it.components }?.let { if (it.enabled) 1 else 0 }
        return listOf(
            StandardRepository("enterprise", "Enterprise", status("pve-enterprise"), "The stable, tested repository for production use; needs a subscription."),
            StandardRepository("no-subscription", "No-Subscription", status("pve-no-subscription"), "Updates for testing and non-production use, without a subscription."),
            StandardRepository("test", "Test", status("pvetest"), "The newest packages, for testing."),
        )
    }

    private fun digest(d: NodeData) = "apt%04d".format(d.version)

    fun setEnabled(node: String, path: String, index: Int, enabled: Boolean, digest: String?): String? {
        val d = node(node) ?: return "no such node"
        if (digest != null && digest != digest(d)) return "detected modified configuration - file changed by other user? Try again."
        val repo = d.files[path]?.getOrNull(index) ?: return "unable to find repository at index $index in $path"
        repo.enabled = enabled
        d.version++
        return null
    }

    fun addStandard(node: String, handle: String, digest: String?): String? {
        val d = node(node) ?: return "no such node"
        if (digest != null && digest != digest(d)) return "detected modified configuration - file changed by other user? Try again."
        val (uri, component) = when (handle) {
            "enterprise" -> "https://enterprise.proxmox.com/debian/pve" to "pve-enterprise"
            "no-subscription" -> "http://download.proxmox.com/debian/pve" to "pve-no-subscription"
            "test" -> "http://download.proxmox.com/debian/pve" to "pvetest"
            else -> return "handle: value '$handle' does not have a value in the enumeration"
        }
        val all = d.files.values.flatten()
        all.firstOrNull { component in it.components }?.let {
            if (it.enabled) return "repository already configured and enabled"
            it.enabled = true
        } ?: d.files.getValue("/etc/apt/sources.list").add(Repo(listOf("deb"), uri, "bookworm", listOf(component)))
        d.version++
        return null
    }

    fun services(node: String): List<NodeService>? = node(node)?.services?.map { (name, state) ->
        NodeService(
            service = name, name = name, desc = DESCRIPTIONS[name] ?: name, state = state,
            activeState = if (state == "running") "active" else "inactive", unitState = "enabled",
        )
    }

    /** Validates [action] on [service]; the state change is applied when its task ends. */
    fun checkServiceAction(node: String, service: String, action: String): String? {
        val d = node(node) ?: return "no such node"
        if (service !in d.services) return "service '$service' is not known"
        if (action !in listOf("start", "stop", "restart", "reload")) return "unknown action '$action'"
        return null
    }

    fun applyServiceAction(node: String, service: String, action: String) {
        val d = node(node) ?: return
        d.services[service] = if (action == "stop") "stopped" else "running"
    }

    fun journal(node: String, last: Int): List<String> {
        val lines = (0 until 800).map { i ->
            val time = "Oct 15 %02d:%02d:%02d".format(8 + i / 240, (i / 4) % 60, (i * 13) % 60)
            when {
                i % 50 == 0 -> "$time $node pvedaemon[1234]: <root@pam> successful auth for user 'root@pam'"
                i % 17 == 0 -> "$time $node pveproxy[2345]: worker 3456 finished"
                i % 29 == 0 -> "$time $node kernel: vmbr0: port 2(tap100i0) entered forwarding state"
                else -> "$time $node pvestatd[1111]: status update time (0.${100 + i % 800} seconds)"
            }
        }
        return listOf("-- cursor: s=aaaa;i=1") + lines.takeLast(last) + "-- cursor: s=aaaa;i=${lines.size}"
    }

    private companion object {
        val DESCRIPTIONS = mapOf(
            "pveproxy" to "PVE API Proxy Server", "pvedaemon" to "PVE API Daemon", "pvestatd" to "PVE Status Daemon",
            "pve-cluster" to "The Proxmox VE cluster filesystem", "corosync" to "Corosync Cluster Engine",
            "pve-firewall" to "Proxmox VE firewall", "pve-ha-crm" to "PVE Cluster HA Resource Manager Daemon",
            "pve-ha-lrm" to "PVE Local HA Resource Manager Daemon", "spiceproxy" to "PVE SPICE Proxy Server",
            "chrony" to "chrony, an NTP client/server", "sshd" to "OpenBSD Secure Shell server", "cron" to "Regular background program processing daemon",
            "postfix" to "Postfix Mail Transport Agent", "systemd-journald" to "Journal Service", "ksmtuned" to "Kernel Samepage Merging (KSM) Tuning Daemon",
        )
    }

    fun dns(node: String): NodeDns? = node(node)?.dns

    fun setDns(node: String, params: Map<String, String>): String? {
        val d = node(node) ?: return "no such node"
        val search = params["search"]?.takeIf { it.isNotBlank() } ?: return "search: property is missing and it is not optional"
        val delete = params["delete"].orEmpty().split(',').toSet()
        fun server(key: String, old: String?) = if (key in delete) null else params[key] ?: old
        listOf("dns1", "dns2", "dns3").forEach { k ->
            params[k]?.let { if (!Regex("^[0-9a-fA-F:.]+$").matches(it)) return "$k: value does not look like a valid IP address" }
        }
        d.dns = NodeDns(search, server("dns1", d.dns.dns1), server("dns2", d.dns.dns2), server("dns3", d.dns.dns3))
        return null
    }

    fun hosts(node: String): NodeHosts? = node(node)?.let { d ->
        if (d.hosts.isEmpty()) d.hosts = "127.0.0.1 localhost.localdomain localhost\n10.0.0.11 pve1.home.lan pve1\n10.0.0.12 pve2.home.lan pve2\n10.0.0.13 pve3.home.lan pve3\n"
        NodeHosts(d.hosts, "hosts%04d".format(d.hostsVersion))
    }

    fun setHosts(node: String, data: String, digest: String?): String? {
        val d = node(node) ?: return "no such node"
        if (digest != null && digest != "hosts%04d".format(d.hostsVersion)) return "hosts file changed by someone else"
        d.hosts = data
        d.hostsVersion++
        return null
    }

    fun time(node: String, nowSeconds: Long): NodeTime? = node(node)?.let { d ->
        val offset = java.util.TimeZone.getTimeZone(d.timezone).getOffset(nowSeconds * 1000) / 1000
        NodeTime(d.timezone, nowSeconds, nowSeconds + offset)
    }

    fun setTimezone(node: String, timezone: String): String? {
        val d = node(node) ?: return "no such node"
        if (timezone !in java.util.TimeZone.getAvailableIDs()) return "timezone: invalid time zone '$timezone'"
        d.timezone = timezone
        return null
    }

    fun disks(node: String): List<NodeDisk>? = node(node)?.disks?.toList()

    fun smart(node: String, devpath: String): SmartData? {
        val disk = node(node)?.disks?.firstOrNull { it.devpath == devpath } ?: return null
        return if (disk.type == "nvme") {
            SmartData("PASSED", "text", text = "SMART/Health Information (NVMe Log 0x02)\nCritical Warning: 0x00\nTemperature: 41 Celsius\nAvailable Spare: 100%\nPercentage Used: 3%\nPower On Hours: 8,214\nUnsafe Shutdowns: 12\nMedia and Data Integrity Errors: 0\n")
        } else {
            fun attr(id: Int, name: String, value: Int, raw: String) =
                SmartAttribute(kotlinx.serialization.json.JsonPrimitive(id), name, kotlinx.serialization.json.JsonPrimitive(value), kotlinx.serialization.json.JsonPrimitive(value), kotlinx.serialization.json.JsonPrimitive(10), raw, "-")
            SmartData(
                "PASSED", "ata",
                listOf(
                    attr(5, "Reallocated_Sector_Ct", 100, "0"),
                    attr(9, "Power_On_Hours", 91, "8214"),
                    attr(194, "Temperature_Celsius", 64, "36"),
                    attr(197, "Current_Pending_Sector", 100, "0"),
                    attr(199, "UDMA_CRC_Error_Count", 100, "0"),
                ),
            )
        }
    }

    /** Validates a disk operation; the change itself happens when its task ends ([markUsed], [markWiped]). */
    fun checkDisks(node: String, devices: List<String>, mustBeUnused: Boolean): String? {
        val d = node(node) ?: return "no such node"
        devices.forEach { dev ->
            val disk = d.disks.firstOrNull { it.devpath == dev } ?: return "disk '$dev' not found"
            if (mustBeUnused && !disk.unused) return "disk '$dev' is already in use (${disk.used})"
        }
        if (devices.isEmpty()) return "no disk given"
        return null
    }

    fun markUsed(node: String, devices: List<String>, how: String) {
        val d = node(node) ?: return
        d.disks.replaceAll { if (it.devpath in devices) it.copy(used = how, gpt = true) else it }
    }

    fun markWiped(node: String, dev: String, gpt: Boolean) {
        val d = node(node) ?: return
        d.disks.replaceAll { if (it.devpath == dev) it.copy(used = null, gpt = gpt) else it }
    }
}
