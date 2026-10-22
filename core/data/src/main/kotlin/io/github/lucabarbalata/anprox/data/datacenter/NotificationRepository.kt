package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.pve.NotifyMatcher
import io.github.lucabarbalata.anprox.model.pve.NotifyMatcherForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTarget
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetType
import io.github.lucabarbalata.anprox.network.api.Forms

/** A target's settings for its editor, with the digest a change is checked against. */
data class NotifyTargetSettings(val form: NotifyTargetForm, val digest: String?)

/**
 * Datacenter → Notifications (milestone M9): where Proxmox VE sends notifications (e-mail,
 * SMTP, Gotify, webhooks) and which notifications go where (matchers).
 */
class NotificationRepository(private val hub: ServerHub) {

    suspend fun targets(serverId: String): List<NotifyTarget> = hub.use(serverId) { it.pve { notifyTargets() } }.sortedBy { it.name }

    /** Sends a test notification through [name]; Proxmox reports a delivery failure as an error. */
    suspend fun test(serverId: String, name: String) {
        hub.use(serverId) { it.pveNoData { testNotifyTarget(name) } }
    }

    suspend fun settings(serverId: String, type: NotifyTargetType, name: String): NotifyTargetSettings {
        val o = hub.use(serverId) { it.pve { notifyEndpoint(type.api, name) } }
        return NotifyTargetSettings(NotifyTargetForm.from(type, o), o.str("digest"))
    }

    suspend fun createTarget(serverId: String, form: NotifyTargetForm) {
        hub.use(serverId) { it.pveNoData { createNotifyEndpoint(form.type.api, Forms.body(form.fields(create = true))) } }
    }

    suspend fun updateTarget(serverId: String, form: NotifyTargetForm, digest: String?) {
        hub.use(serverId) { it.pveNoData { updateNotifyEndpoint(form.type.api, form.name, Forms.body(form.fields(create = false, digest = digest))) } }
    }

    suspend fun deleteTarget(serverId: String, target: NotifyTarget) {
        val type = target.kind ?: return
        hub.use(serverId) { it.pveNoData { deleteNotifyEndpoint(type.api, target.name) } }
    }

    suspend fun matchers(serverId: String): List<NotifyMatcher> =
        hub.use(serverId) { it.pve { notifyMatchers() } }.map(NotifyMatcher::from).sortedBy { it.name }

    suspend fun createMatcher(serverId: String, form: NotifyMatcherForm) {
        hub.use(serverId) { it.pveNoData { createNotifyMatcher(Forms.body(form.fields(create = true))) } }
    }

    suspend fun updateMatcher(serverId: String, matcher: NotifyMatcher, form: NotifyMatcherForm) {
        hub.use(serverId) { it.pveNoData { updateNotifyMatcher(matcher.name, Forms.body(form.fields(create = false, digest = matcher.digest))) } }
    }

    suspend fun deleteMatcher(serverId: String, name: String) {
        hub.use(serverId) { it.pveNoData { deleteNotifyMatcher(name) } }
    }

    /** Known `type` values and job IDs, to suggest in a matcher's fields. */
    suspend fun fieldValues(serverId: String): List<Pair<String, String>> =
        hub.use(serverId) { it.pve { notifyFieldValues() } }.mapNotNull { o -> o.str("field")?.let { f -> o.str("value")?.let { f to it } } }
}
