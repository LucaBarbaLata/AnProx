package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.NotificationRepository
import io.github.lucabarbalata.anprox.model.pve.NotifyMatcher
import io.github.lucabarbalata.anprox.model.pve.NotifyMatcherForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTarget
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetType
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A target being added ([create]) or changed; [loading] while its settings are read. */
data class TargetEdit(val form: NotifyTargetForm, val create: Boolean, val digest: String? = null, val loading: Boolean = false)

/** A matcher being added ([existing] null) or changed. */
data class MatcherEdit(val form: NotifyMatcherForm, val existing: NotifyMatcher? = null)

data class NotificationsState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val targets: List<NotifyTarget> = emptyList(),
    val matchers: List<NotifyMatcher> = emptyList(),
    /** Known `type` values and job IDs, offered when writing a matcher's fields. */
    val fieldValues: List<Pair<String, String>> = emptyList(),
    val busy: Boolean = false,
    val targetEdit: TargetEdit? = null,
    val matcherEdit: MatcherEdit? = null,
    val deleteTarget: NotifyTarget? = null,
    val deleteMatcher: NotifyMatcher? = null,
    val result: TaskResult? = null,
)

/** Datacenter → Notifications: targets (e-mail, SMTP, Gotify, webhooks) and matchers. */
class NotificationsViewModel(
    private val serverId: String,
    private val repo: NotificationRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(NotificationsState())
    val state: StateFlow<NotificationsState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val targets = async { repo.targets(serverId) }
                    val matchers = async { repo.matchers(serverId) }
                    val values = async {
                        try {
                            repo.fieldValues(serverId)
                        } catch (_: ApiException) {
                            emptyList()
                        }
                    }
                    ui.update { it.copy(loading = false, targets = targets.await(), matchers = matchers.await(), fieldValues = values.await()) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun startAddTarget() = ui.update { it.copy(targetEdit = TargetEdit(NotifyTargetForm(), create = true)) }

    /** Opens [target]'s settings; secrets are left empty and kept unless typed again. */
    fun startEditTarget(target: NotifyTarget) {
        val type = target.kind ?: return
        ui.update { it.copy(targetEdit = TargetEdit(NotifyTargetForm(type, target.name), create = false, loading = true)) }
        viewModelScope.launch {
            try {
                val settings = repo.settings(serverId, type, target.name)
                ui.update { s -> if (s.targetEdit?.form?.name == target.name) s.copy(targetEdit = TargetEdit(settings.form, create = false, digest = settings.digest)) else s }
            } catch (e: ApiException) {
                ui.update { it.copy(targetEdit = null, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
        }
    }

    fun editTarget(change: NotifyTargetForm.() -> NotifyTargetForm) = ui.update { s -> s.copy(targetEdit = s.targetEdit?.let { it.copy(form = it.form.change()) }) }

    fun setTargetType(type: NotifyTargetType) = editTarget { copy(type = type) }

    fun cancelTarget() = ui.update { it.copy(targetEdit = null) }

    fun saveTarget() {
        val edit = ui.value.targetEdit ?: return
        if (edit.loading || edit.form.problem(edit.create) != null) return
        ui.update { it.copy(targetEdit = null) }
        change(if (edit.create) R.string.notify_target_added else R.string.notify_target_saved) {
            if (edit.create) repo.createTarget(serverId, edit.form) else repo.updateTarget(serverId, edit.form, edit.digest)
        }
    }

    fun test(target: NotifyTarget) = change(R.string.notify_test_sent, reload = false) { repo.test(serverId, target.name) }

    fun requestDeleteTarget(target: NotifyTarget) = ui.update { it.copy(deleteTarget = target) }

    fun dismissDeleteTarget() = ui.update { it.copy(deleteTarget = null) }

    fun deleteTarget() {
        val target = ui.value.deleteTarget ?: return
        ui.update { it.copy(deleteTarget = null) }
        change(R.string.notify_target_deleted) { repo.deleteTarget(serverId, target) }
    }

    fun startAddMatcher() = ui.update { it.copy(matcherEdit = MatcherEdit(NotifyMatcherForm())) }

    fun startEditMatcher(matcher: NotifyMatcher) = ui.update { it.copy(matcherEdit = MatcherEdit(NotifyMatcherForm.from(matcher), matcher)) }

    fun editMatcher(change: NotifyMatcherForm.() -> NotifyMatcherForm) = ui.update { s -> s.copy(matcherEdit = s.matcherEdit?.let { it.copy(form = it.form.change()) }) }

    fun cancelMatcher() = ui.update { it.copy(matcherEdit = null) }

    fun saveMatcher() {
        val edit = ui.value.matcherEdit ?: return
        if (edit.form.problem() != null) return
        ui.update { it.copy(matcherEdit = null) }
        change(if (edit.existing == null) R.string.notify_matcher_added else R.string.notify_matcher_saved) {
            if (edit.existing == null) repo.createMatcher(serverId, edit.form) else repo.updateMatcher(serverId, edit.existing, edit.form)
        }
    }

    fun requestDeleteMatcher(matcher: NotifyMatcher) = ui.update { it.copy(deleteMatcher = matcher) }

    fun dismissDeleteMatcher() = ui.update { it.copy(deleteMatcher = null) }

    fun deleteMatcher() {
        val matcher = ui.value.deleteMatcher ?: return
        ui.update { it.copy(deleteMatcher = null) }
        change(R.string.notify_matcher_deleted) { repo.deleteMatcher(serverId, matcher.name) }
    }

    fun consumeResult() = ui.update { it.copy(result = null) }

    private fun change(done: Int, reload: Boolean = true, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(UiText.res(done), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            if (reload) load()
        }
    }
}
