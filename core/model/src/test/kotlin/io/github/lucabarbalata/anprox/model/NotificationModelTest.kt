package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.pve.NotifyMatcher
import io.github.lucabarbalata.anprox.model.pve.NotifyMatcherForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `target forms check what Proxmox needs and repeat array keys`() {
        assertEquals(NotifyTargetForm.Problem.NAME, NotifyTargetForm(name = "1x").problem(create = true))
        assertEquals(NotifyTargetForm.Problem.RECIPIENT, NotifyTargetForm(name = "m").problem(create = true))
        assertEquals(NotifyTargetForm.Problem.FROM, NotifyTargetForm(NotifyTargetType.SMTP, "m", mailtoUser = "root@pam", server = "s").problem(create = true))
        assertEquals(NotifyTargetForm.Problem.TOKEN, NotifyTargetForm(NotifyTargetType.GOTIFY, "g", server = "https://g").problem(create = true))
        assertNull(NotifyTargetForm(NotifyTargetType.GOTIFY, "g", server = "https://g").problem(create = false))
        assertEquals(NotifyTargetForm.Problem.HEADER, NotifyTargetForm(NotifyTargetType.WEBHOOK, "w", url = "https://w", headers = "no colon").problem(create = true))

        val mail = NotifyTargetForm(name = "m", mailto = "a@x.org; b@x.org", mailtoUser = "root@pam")
        assertEquals(
            listOf("name" to "m", "mailto" to "a@x.org", "mailto" to "b@x.org", "mailto-user" to "root@pam"),
            mail.fields(create = true),
        )
        // Changing: emptied fields are deleted, the digest comes along.
        assertEquals(
            listOf("mailto" to "a@x.org", "mailto" to "b@x.org", "mailto-user" to "root@pam", "delete" to "comment", "delete" to "disable", "delete" to "from-address", "delete" to "author", "digest" to "d"),
            mail.fields(create = false, digest = "d"),
        )
        val hook = NotifyTargetForm(NotifyTargetType.WEBHOOK, "w", url = "https://w", method = "put", headers = "X-A: 1", body = "hi")
        assertEquals(
            listOf("name" to "w", "url" to "https://w", "method" to "put", "header" to "name=X-A,value=MQ==", "body" to "aGk="),
            hook.fields(create = true),
        )
    }

    @Test
    fun `matchers as Proxmox sends them`() {
        val m = NotifyMatcher.from(
            json.decodeFromString<JsonObject>(
                """{"name":"m","target":["a","b"],"match-severity":"warning,error","match-field":"exact:type=vzdump,replication",
                   "match-calendar":"mon..fri 8-17","mode":"any","invert-match":1,"origin":"user-created"}""",
            ),
        )
        assertEquals(listOf("a", "b"), m.targets)
        assertEquals(listOf("warning", "error"), m.severities)
        assertEquals(listOf("exact:type=vzdump,replication"), m.fields)   // not split at the comma
        assertEquals(listOf("mon..fri 8-17"), m.calendar)                   // nor at the space
        assertEquals(true, m.invert)

        val form = NotifyMatcherForm.from(m)
        assertNull(form.problem())
        assertEquals(
            listOf(
                "name" to "m", "target" to "a", "target" to "b", "match-severity" to "warning,error",
                "match-field" to "exact:type=vzdump,replication", "match-calendar" to "mon..fri 8-17", "mode" to "any", "invert-match" to "1",
            ),
            form.fields(create = true),
        )
        assertEquals(NotifyMatcherForm.Problem.TARGET, form.copy(targets = emptySet()).problem())
    }
}
