package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.node.CertificateRepository
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.AcmeAccountForm
import io.github.lucabarbalata.anprox.model.pve.AcmePluginForm
import io.github.lucabarbalata.anprox.model.pve.CertificateInfo
import io.github.lucabarbalata.anprox.model.pve.CustomCertificateForm
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Node certificates and ACME against the mock. */
class CertificateTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE, taskDurationMillis = 0).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun hub(user: String = "root", realm: String = "pam"): ServerHub {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return hub
    }

    private suspend fun ServerHub.await(upid: String) = GuestRepository(this).awaitTask("pve", Upid.parse(upid)!!.node, upid)

    @Test
    fun `certificates, an ACME renewal and a custom certificate`() = runBlocking {
        val hub = hub()
        val repo = CertificateRepository(hub)
        val now = System.currentTimeMillis() / 1000

        val certs = repo.certificates("pve", "pve1")
        assertEquals(listOf(CertificateInfo.Role.IN_USE, CertificateInfo.Role.NODE, CertificateInfo.Role.CLUSTER_CA), certs.map { it.role })
        val inUse = certs.first()
        assertEquals("pve1.example.com", inUse.commonName)
        assertEquals("R11", inUse.issuerName)
        assertTrue(inUse.expiresWithin(30, now))
        assertEquals("pve1.lan", certs[1].commonName)
        // pve2 has no custom certificate: the node's own is the one in use.
        assertFalse(repo.certificates("pve", "pve2").any { it.role == CertificateInfo.Role.IN_USE })

        val config = repo.acmeConfig("pve", "pve1")
        assertEquals("default", config.accountOrDefault)
        assertEquals(listOf("pve1.example.com" to "cloudflare"), config.domains.map { it.domain to it.plugin })

        // 20 days left, so renewing is allowed without forcing it; the new one is valid for 90 days.
        assertTrue(hub.await(repo.renew("pve", "pve1", force = false)).succeeded)
        val renewed = repo.certificates("pve", "pve1").first()
        assertFalse(renewed.expiresWithin(80, now))
        // Now a renewal needs force.
        assertFailsWith<ApiException> { repo.renew("pve", "pve1", force = false) }

        // A custom certificate replaces the ACME one (the form forces it); removing it goes back.
        assertEquals(CustomCertificateForm.Problem.CERTIFICATE, CustomCertificateForm("not a pem").problem())
        val form = CustomCertificateForm(PEM, KEY)
        assertNull(form.problem())
        repo.uploadCustom("pve", "pve1", form)
        assertEquals("uploaded-pve1.example.com", repo.certificates("pve", "pve1").first().commonName)
        repo.removeCustom("pve", "pve1")
        assertEquals(CertificateInfo.Role.NODE, repo.certificates("pve", "pve1").first().role)
        // Without a key and with no key left on the node, Proxmox refuses.
        assertFailsWith<ApiException> { repo.uploadCustom("pve", "pve1", CustomCertificateForm(PEM)) }
        Unit
    }

    @Test
    fun `ACME domains on a node, then an order`() = runBlocking {
        val hub = hub()
        val repo = CertificateRepository(hub)
        var config = repo.acmeConfig("pve", "pve2")
        assertTrue(config.domains.isEmpty())
        assertEquals("acmedomain0", config.freeKey)
        // Ordering without domains is refused.
        assertFailsWith<ApiException> { repo.order("pve", "pve2", force = false) }

        repo.addDomain("pve", "pve2", config, "PVE2.Example.com", "standalone", null)
        config = repo.acmeConfig("pve", "pve2")
        assertEquals("pve2.example.com", config.domains.single().domain)
        assertTrue(config.domains.single().isStandalone)
        assertEquals("default", config.account)                  // the account came with the first domain
        repo.addDomain("pve", "pve2", config, "www.pve2.example.com", "cloudflare", "_acme.example.net")
        config = repo.acmeConfig("pve", "pve2")
        assertEquals(listOf("acmedomain0", "acmedomain1"), config.domains.map { it.key })
        assertEquals("_acme.example.net", config.domains[1].alias)
        // A stale digest is refused (someone changed the config in between).
        val stale = config
        repo.removeDomain("pve", "pve2", config, config.domains[1])
        assertFailsWith<ApiException> { repo.removeDomain("pve", "pve2", stale, stale.domains[0]) }
        config = repo.acmeConfig("pve", "pve2")
        assertEquals(listOf("pve2.example.com"), config.domains.map { it.domain })

        val upid = repo.order("pve", "pve2", force = false)
        assertTrue(hub.await(upid).succeeded)
        assertEquals("pve2.example.com", repo.certificates("pve", "pve2").first().commonName)
        // Now a custom certificate exists: a second order needs force.
        assertFailsWith<ApiException> { repo.order("pve", "pve2", force = false) }
        assertTrue(hub.await(repo.order("pve", "pve2", force = true)).succeeded)
        Unit
    }

    @Test
    fun `ACME accounts and DNS plugins`() = runBlocking {
        val hub = hub()
        val repo = CertificateRepository(hub)
        val accounts = repo.accounts("pve")
        assertEquals(listOf("default"), accounts.map { it.name })
        assertEquals(listOf("admin@example.com"), accounts.single().contacts)
        assertEquals("valid", accounts.single().status)

        val directories = repo.directories("pve")
        assertEquals(2, directories.size)
        val meta = repo.meta("pve", directories[1].url)
        assertTrue(meta.termsOfService!!.startsWith("https://"))

        // The terms must be accepted: the form says so, and Let's Encrypt refuses without them.
        val form = AcmeAccountForm(name = "staging", email = "ops@example.com", directory = directories[1].url)
        assertEquals(AcmeAccountForm.Problem.TOS, form.problem(meta))
        assertFalse(hub.await(repo.register("pve", form, meta)).succeeded)
        val accepted = form.copy(acceptTos = true)
        assertNull(accepted.problem(meta))
        assertTrue(hub.await(repo.register("pve", accepted, meta)).succeeded)
        assertEquals(listOf("default", "staging"), repo.accounts("pve").map { it.name })
        assertFailsWith<ApiException> { repo.register("pve", accepted, meta) }   // the name is taken
        assertTrue(hub.await(repo.deactivate("pve", "staging")).succeeded)
        assertEquals(listOf("default"), repo.accounts("pve").map { it.name })

        // DNS plugins: the provider list, a new plugin with its credentials, a change, removal.
        val apis = repo.dnsApis("pve")
        assertTrue(apis.any { it.id == "hetzner" && "HETZNER_Token" in it.fields })
        val cloudflare = repo.plugins("pve").single { it.id == "cloudflare" }
        assertEquals("CF_Token=mock-token\nCF_Account_ID=1234", cloudflare.data)
        assertEquals(AcmePluginForm.Problem.DATA, AcmePluginForm("hz", "hetzner", "just a token").problem())
        repo.createPlugin("pve", AcmePluginForm("hz", "hetzner", "HETZNER_Token=abc\n"))
        val hz = repo.plugins("pve").single { it.id == "hz" }
        assertTrue(hz.isDns)
        assertEquals("HETZNER_Token=abc", hz.data)
        repo.updatePlugin("pve", hz, AcmePluginForm.from(hz).copy(data = "HETZNER_Token=new", validationDelay = "60"))
        val changed = repo.plugins("pve").single { it.id == "hz" }
        assertEquals("HETZNER_Token=new", changed.data)
        assertEquals(60, changed.validationDelay)
        // The old digest no longer matches.
        assertFailsWith<ApiException> { repo.updatePlugin("pve", hz, AcmePluginForm.from(hz)) }
        repo.deletePlugin("pve", "hz")
        assertFalse(repo.plugins("pve").any { it.id == "hz" })
    }

    @Test
    fun `a read-only user can look but not change`() = runBlocking {
        val hub = hub(user = "auditor", realm = "pve")
        val repo = CertificateRepository(hub)
        assertTrue(repo.certificates("pve", "pve1").isNotEmpty())
        assertFailsWith<ApiException.Forbidden> { repo.renew("pve", "pve1", force = true) }
        assertFailsWith<ApiException.Forbidden> { repo.deletePlugin("pve", "cloudflare") }
        Unit
    }

    companion object {
        private const val PEM = "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIU\n-----END CERTIFICATE-----"
        private const val KEY = "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49\n-----END PRIVATE KEY-----"
    }
}
