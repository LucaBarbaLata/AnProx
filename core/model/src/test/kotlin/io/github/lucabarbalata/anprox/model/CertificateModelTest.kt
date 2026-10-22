package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.pve.AcmeAccount
import io.github.lucabarbalata.anprox.model.pve.AcmeAccountForm
import io.github.lucabarbalata.anprox.model.pve.AcmeMeta
import io.github.lucabarbalata.anprox.model.pve.AcmePlugin
import io.github.lucabarbalata.anprox.model.pve.CertificateInfo
import io.github.lucabarbalata.anprox.model.pve.NodeAcmeConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CertificateModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `certificate info as Proxmox sends it`() {
        val info = json.decodeFromString<List<CertificateInfo>>(
            """[{"filename":"pveproxy-ssl.pem","fingerprint":"AB:CD","issuer":"/C=US/O=Let's Encrypt/CN=R11",
               "notafter":1767225600,"notbefore":1759449600,"pem":"-----BEGIN CERTIFICATE-----…",
               "public-key-bits":2048,"public-key-type":"rsaEncryption","san":["pve.example.com"],
               "subject":"/CN=pve.example.com"}]""",
        ).single()
        assertEquals(CertificateInfo.Role.IN_USE, info.role)
        assertEquals("pve.example.com", info.commonName)
        assertEquals("R11", info.issuerName)
        assertEquals(2048, info.publicKeyBits)
        assertEquals(10L, info.daysLeft(1767225600 - 10 * 86_400L))
        assertEquals(-1L, info.daysLeft(1767225600 + 3_600))
        // The other way OpenSSL writes names, and an issuer with no CN.
        assertEquals("pve1.lan", CertificateInfo.commonName("OU = PVE Cluster Node, O = Proxmox Virtual Environment, CN = pve1.lan"))
        assertEquals("Example CA", CertificateInfo(issuer = "/C=US/O=Example CA").issuerName)
    }

    @Test
    fun `node ACME config with both kinds of domain lines`() {
        val config = NodeAcmeConfig.from(
            json.decodeFromString<JsonObject>(
                """{"acme":"account=prod,domains=old.example.com;Legacy.example.com","acmedomain0":"pve.example.com,plugin=cf",
                   "acmedomain2":"domain=www.example.com,plugin=cf,alias=_acme.example.net","digest":"abc"}""",
            ),
        )
        assertEquals("prod", config.account)
        assertEquals(listOf("old.example.com", "legacy.example.com", "pve.example.com", "www.example.com"), config.domains.map { it.domain })
        assertEquals(listOf("acme", "acme", "acmedomain0", "acmedomain2"), config.domains.map { it.key })
        assertEquals("acmedomain1", config.freeKey)

        assertEquals(
            mapOf("acmedomain1" to "domain=new.example.com,plugin=standalone", "digest" to "abc"),
            config.addDomainParams("New.Example.com ", "standalone", " "),
        )
        assertEquals(mapOf("delete" to "acmedomain2", "digest" to "abc"), config.removeDomainParams(config.domains[3]))
        assertEquals(mapOf("acme" to "account=prod,domains=legacy.example.com", "digest" to "abc"), config.removeDomainParams(config.domains[0]))
        assertEquals(mapOf("acme" to "account=staging,domains=old.example.com;Legacy.example.com", "digest" to "abc"), config.accountParams("staging"))

        // No acme line yet: the first domain brings the default account along.
        val empty = NodeAcmeConfig.from(JsonObject(emptyMap()))
        assertEquals("default", empty.accountOrDefault)
        assertEquals("account=default", empty.addDomainParams("a.example.com", "standalone", null)["acme"])
        // The last legacy domain gone and no account: the whole line goes.
        val only = NodeAcmeConfig.from(json.decodeFromString<JsonObject>("""{"acme":"domains=a.example.com"}"""))
        assertEquals(mapOf("delete" to "acme"), only.removeDomainParams(only.domains.single()))
    }

    @Test
    fun `accounts, registration and plugin data`() {
        val account = AcmeAccount.from(
            "default",
            json.decodeFromString<JsonObject>(
                """{"account":{"contact":["mailto:a@example.com","mailto:b@example.com"],"status":"valid"},
                   "directory":"https://acme-v02.api.letsencrypt.org/directory","location":"https://x/acct/1","tos":"https://x/tos.pdf"}""",
            ),
        )
        assertEquals(listOf("a@example.com", "b@example.com"), account.contacts)
        assertEquals("valid", account.status)

        val meta = AcmeMeta(termsOfService = "https://x/tos.pdf")
        assertEquals(AcmeAccountForm.Problem.NAME, AcmeAccountForm(name = "1st").problem(meta))
        assertEquals(AcmeAccountForm.Problem.EMAIL, AcmeAccountForm(email = "nope").problem(meta))
        assertEquals(AcmeAccountForm.Problem.TOS, AcmeAccountForm(email = "a@example.com").problem(meta))
        assertEquals(AcmeAccountForm.Problem.EAB, AcmeAccountForm(email = "a@example.com").problem(AcmeMeta(externalAccountRequired = true)))
        val form = AcmeAccountForm(email = "a@example.com; b@example.com", acceptTos = true)
        assertNull(form.problem(meta))
        assertEquals(
            mapOf("name" to "default", "contact" to "a@example.com,b@example.com", "directory" to AcmeAccountForm.LETS_ENCRYPT, "tos_url" to "https://x/tos.pdf"),
            form.params(meta),
        )

        val encoded = AcmePlugin.encodeData(" CF_Token=abc \n\nCF_Account_ID=1\n")
        assertEquals("CF_Token=abc\nCF_Account_ID=1", AcmePlugin.decodeData(encoded))
        // Something that isn't base64 is shown as it is.
        assertEquals("CF_Token=abc", AcmePlugin.decodeData("CF_Token=abc"))
    }
}
