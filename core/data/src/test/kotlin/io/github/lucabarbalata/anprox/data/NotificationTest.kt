package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.NotificationRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.NotifyMatcherForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetType
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
import kotlin.test.assertTrue

/** Notification targets and matchers against the mock. */
class NotificationTest {
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

    @Test
    fun `targets of every kind, with repeated recipients and kept secrets`() = runBlocking {
        val repo = NotificationRepository(hub())
        val targets = repo.targets("pve")
        assertEquals(listOf("mail-to-root", "phone"), targets.map { it.name })
        assertTrue(targets.first().builtin)

        // Two recipients travel as two `mailto` fields.
        repo.createTarget("pve", NotifyTargetForm(NotifyTargetType.SMTP, "ops-mail", mailto = "a@example.com, b@example.com", fromAddress = "pve@example.com", server = "smtp.example.com", port = "587", mode = "starttls", username = "pve", password = "s3cret"))
        var smtp = repo.settings("pve", NotifyTargetType.SMTP, "ops-mail")
        assertEquals("a@example.com, b@example.com", smtp.form.mailto)
        assertEquals("starttls", smtp.form.mode)
        assertEquals("", smtp.form.password)                 // never sent back
        // A change without a password keeps it; emptying the user name deletes it.
        repo.updateTarget("pve", smtp.form.copy(username = "", comment = "Ops"), smtp.digest)
        smtp = repo.settings("pve", NotifyTargetType.SMTP, "ops-mail")
        assertEquals("", smtp.form.username)
        assertEquals("Ops", smtp.form.comment)
        assertFailsWith<ApiException> { repo.updateTarget("pve", smtp.form, "stale-digest") }

        repo.createTarget("pve", NotifyTargetForm(NotifyTargetType.WEBHOOK, "hook", url = "https://hooks.example.com/pve", headers = "Content-Type: application/json\nX-Token: abc", body = "{\"text\":\"{{ title }}\"}", secrets = "token=xyz"))
        val hook = repo.settings("pve", NotifyTargetType.WEBHOOK, "hook").form
        assertEquals("Content-Type: application/json\nX-Token: abc", hook.headers)
        assertEquals("{\"text\":\"{{ title }}\"}", hook.body)
        assertEquals("", hook.secrets)

        repo.test("pve", "hook")
        assertEquals(listOf("hook"), pve.state.notificationsTested())
        // A Gotify server that can't be reached: the test reports it.
        val phone = repo.settings("pve", NotifyTargetType.GOTIFY, "phone")
        repo.updateTarget("pve", phone.form.copy(server = "https://unreachable.example.com"), phone.digest)
        assertFailsWith<ApiException> { repo.test("pve", "phone") }

        // The built-in target stays; user-created ones go.
        assertFailsWith<ApiException> { repo.deleteTarget("pve", targets.first()) }
        repo.deleteTarget("pve", repo.targets("pve").single { it.name == "hook" })
        assertFalse(repo.targets("pve").any { it.name == "hook" })
    }

    @Test
    fun `matchers route notifications to targets`() = runBlocking {
        val repo = NotificationRepository(hub())
        val default = repo.matchers("pve").single()
        assertEquals("default-matcher", default.name)
        assertEquals(listOf("mail-to-root"), default.targets)
        assertTrue(default.builtin)

        assertTrue(repo.fieldValues("pve").contains("type" to "vzdump"))
        val form = NotifyMatcherForm("backups-failed", targets = setOf("phone"), severities = setOf("error", "warning"), fields = "exact:type=vzdump", calendar = "mon..fri 8-17")
        assertEquals(NotifyMatcherForm.Problem.FIELD, form.copy(fields = "type is vzdump").problem())
        repo.createMatcher("pve", form)
        val created = repo.matchers("pve").single { it.name == "backups-failed" }
        assertEquals(listOf("warning", "error"), created.severities)   // Proxmox's order
        assertEquals(listOf("exact:type=vzdump"), created.fields)
        assertEquals(listOf("mon..fri 8-17"), created.calendar)

        repo.updateMatcher("pve", created, NotifyMatcherForm.from(created).copy(targets = setOf("phone", "mail-to-root"), calendar = "", invert = true))
        val changed = repo.matchers("pve").single { it.name == "backups-failed" }
        assertEquals(listOf("mail-to-root", "phone"), changed.targets)
        assertTrue(changed.calendar.isEmpty())
        assertTrue(changed.invert)
        // A target that doesn't exist is refused.
        assertFailsWith<ApiException> { repo.createMatcher("pve", form.copy(name = "x", targets = setOf("nowhere"))) }
        assertFailsWith<ApiException> { repo.deleteMatcher("pve", "default-matcher") }
        repo.deleteMatcher("pve", "backups-failed")
        assertEquals(listOf("default-matcher"), repo.matchers("pve").map { it.name })
    }

    @Test
    fun `a read-only user can look but not change`() = runBlocking {
        val repo = NotificationRepository(hub(user = "auditor", realm = "pve"))
        assertTrue(repo.targets("pve").isNotEmpty())
        assertFailsWith<ApiException.Forbidden> { repo.deleteMatcher("pve", "default-matcher") }
        Unit
    }
}
