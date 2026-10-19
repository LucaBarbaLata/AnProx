package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.DatacenterOptions
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatacenterOptionsTest {

    @Test
    fun `reads format properties as objects or property strings`() {
        // PVE 8 answers parsed objects; datacenter.cfg (and old versions) use property strings.
        val objects = ProxmoxJson.decodeFromString<JsonObject>(
            """{"console":"xtermjs","migration":{"type":"insecure","network":"10.1.0.0/24"},"ha":{"shutdown_policy":"migrate"},"next-id":{"lower":"1000","upper":"2000"},"max_workers":4,"digest":"abc"}""",
        )
        val a = DatacenterOptions.fromJson(objects)
        assertEquals("insecure", a.migrationType)
        assertEquals("10.1.0.0/24", a.migrationNetwork)
        assertEquals("migrate", a.haShutdownPolicy)
        assertEquals("1000", a.nextIdLower)
        assertEquals("4", a.maxWorkers)
        assertEquals("abc", a.digest)

        val strings = ProxmoxJson.decodeFromString<JsonObject>("""{"migration":"secure,network=10.2.0.0/16","ha":"shutdown_policy=freeze"}""")
        val b = DatacenterOptions.fromJson(strings)
        assertEquals("secure", b.migrationType)   // the bare first part is the type
        assertEquals("10.2.0.0/16", b.migrationNetwork)
        assertEquals("freeze", b.haShutdownPolicy)
    }

    @Test
    fun `changes send property strings and delete what was emptied`() {
        val old = DatacenterOptions(console = "html5", migrationType = "secure", migrationNetwork = "10.0.0.0/24", emailFrom = "a@b.c", digest = "d1")
        assertEquals(emptyMap(), DatacenterOptions.changes(old, old))
        val new = old.copy(console = "", migrationNetwork = "10.9.0.0/24", haShutdownPolicy = "migrate", nextIdLower = "500", emailFrom = "")
        assertEquals(
            mapOf(
                "migration" to "type=secure,network=10.9.0.0/24",
                "ha" to "shutdown_policy=migrate",
                "next-id" to "lower=500",
                "delete" to "console,email_from",
                "digest" to "d1",
            ),
            DatacenterOptions.changes(old, new),
        )
        assertEquals(mapOf("delete" to "migration", "digest" to "d1"), DatacenterOptions.changes(old, old.copy(migrationType = "", migrationNetwork = "")))
    }

    @Test
    fun `problems are found before Proxmox refuses them`() {
        assertNull(DatacenterOptions(macPrefix = "BC:24:11", httpProxy = "http://proxy:3128", migrationNetwork = "10.0.0.0/24").problem())
        assertEquals(DatacenterOptions.Problem.MAC_PREFIX, DatacenterOptions(macPrefix = "01:00").problem())   // multicast
        assertEquals(DatacenterOptions.Problem.HTTP_PROXY, DatacenterOptions(httpProxy = "proxy:3128").problem())
        assertEquals(DatacenterOptions.Problem.EMAIL, DatacenterOptions(emailFrom = "nobody").problem())
        assertEquals(DatacenterOptions.Problem.MIGRATION_NETWORK, DatacenterOptions(migrationNetwork = "10.0.0.0").problem())
        assertEquals(DatacenterOptions.Problem.NEXT_ID, DatacenterOptions(nextIdLower = "5000", nextIdUpper = "4000").problem())
        assertEquals(DatacenterOptions.Problem.MAX_WORKERS, DatacenterOptions(maxWorkers = "0").problem())
    }
}
