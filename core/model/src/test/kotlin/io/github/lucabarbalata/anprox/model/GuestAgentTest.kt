package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.AgentInterface
import io.github.lucabarbalata.anprox.model.pve.AgentOsInfo
import io.github.lucabarbalata.anprox.model.pve.AgentPassword
import io.github.lucabarbalata.anprox.model.pve.AgentResult
import io.github.lucabarbalata.anprox.model.pve.AgentTrimResult
import io.github.lucabarbalata.anprox.model.pve.FsFreezeStatus
import io.github.lucabarbalata.anprox.model.pve.GuestNic
import io.github.lucabarbalata.anprox.model.pve.LxcInterface
import kotlin.test.Test
import kotlin.test.assertEquals

class GuestAgentTest {

    @Test
    fun `agent interfaces drop loopback and link-local addresses`() {
        // Shape of GET /nodes/pve1/qemu/100/agent/network-get-interfaces on PVE 8.
        val json = """
            {"data":{"result":[
              {"name":"lo","hardware-address":"00:00:00:00:00:00","ip-addresses":[
                {"ip-address":"127.0.0.1","ip-address-type":"ipv4","prefix":8},
                {"ip-address":"::1","ip-address-type":"ipv6","prefix":128}],
               "statistics":{"rx-bytes":1}},
              {"name":"ens18","hardware-address":"bc:24:11:aa:bb:cc","ip-addresses":[
                {"ip-address":"10.0.0.100","ip-address-type":"ipv4","prefix":24},
                {"ip-address":"2001:db8::100","ip-address-type":"ipv6","prefix":64},
                {"ip-address":"fe80::be24:11ff:feaa:bbcc","ip-address-type":"ipv6","prefix":64}]},
              {"name":"docker0","hardware-address":"02:42:00:00:00:01","ip-addresses":[]}
            ]}}
        """.trimIndent()
        val result = ProxmoxJson.decodeFromString<ApiEnvelope<AgentResult<List<AgentInterface>>>>(json).data!!.result!!
        val nics = GuestNic.fromAgent(result)
        assertEquals(1, nics.size)
        assertEquals("ens18", nics[0].name)
        assertEquals(listOf("10.0.0.100"), nics[0].ipv4)
        assertEquals(listOf("2001:db8::100"), nics[0].ipv6)
        assertEquals("bc:24:11:aa:bb:cc", nics[0].mac)
    }

    @Test
    fun `os info prefers the pretty name, Windows falls back to name and version`() {
        val linux = ProxmoxJson.decodeFromString<ApiEnvelope<AgentResult<AgentOsInfo>>>(
            """{"data":{"result":{"id":"debian","name":"Debian GNU/Linux","pretty-name":"Debian GNU/Linux 12 (bookworm)","kernel-release":"6.1.0-25-amd64"}}}""",
        ).data!!.result!!
        assertEquals("Debian GNU/Linux 12 (bookworm)", linux.displayName)

        val windows = AgentOsInfo(name = "Microsoft Windows 11", version = "Pro")
        assertEquals("Microsoft Windows 11 Pro", windows.displayName)
    }

    @Test
    fun `container interfaces strip prefixes`() {
        val list = ProxmoxJson.decodeFromString<ApiEnvelope<List<LxcInterface>>>(
            """{"data":[{"name":"lo","hwaddr":"00:00:00:00:00:00","inet":"127.0.0.1/8","inet6":"::1/128"},
                        {"name":"eth0","hwaddr":"bc:24:11:00:00:01","inet":"10.0.1.10/24","inet6":"fe80::1/64"}]}""",
        ).data!!
        val nics = GuestNic.fromLxc(list)
        assertEquals(listOf("eth0"), nics.map { it.name })
        assertEquals(listOf("10.0.1.10"), nics[0].ipv4)
        assertEquals(emptyList(), nics[0].ipv6)
    }

    @Test
    fun `freeze status, trim results and password checks`() {
        assertEquals(FsFreezeStatus.FROZEN, FsFreezeStatus.fromApi("frozen"))
        assertEquals(FsFreezeStatus.THAWED, FsFreezeStatus.fromApi("thawed"))
        assertEquals(FsFreezeStatus.UNKNOWN, FsFreezeStatus.fromApi(null))

        // Shape of POST /nodes/pve1/qemu/100/agent/fstrim on a Linux guest.
        val json = """{"data":{"result":{"paths":[{"path":"/","trimmed":1048576,"minimum":0},{"path":"/mnt/ro","error":"Read-only file system"}]}}}"""
        val trim = ProxmoxJson.decodeFromString<ApiEnvelope<AgentResult<AgentTrimResult>>>(json).data!!.result!!
        assertEquals(1048576L, trim.paths[0].trimmed)
        assertEquals("Read-only file system", trim.paths[1].error)

        assertEquals(AgentPassword.Problem.NO_USER, AgentPassword.validate(" ", "secret", "secret"))
        assertEquals(AgentPassword.Problem.TOO_SHORT, AgentPassword.validate("root", "abcd", "abcd"))
        assertEquals(AgentPassword.Problem.MISMATCH, AgentPassword.validate("root", "secret1", "secret2"))
        assertEquals(null, AgentPassword.validate("root", "secret", "secret"))
    }
}
