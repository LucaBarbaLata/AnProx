package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.FirewallRepository
import io.github.lucabarbalata.anprox.model.pve.FirewallMacro
import io.github.lucabarbalata.anprox.model.pve.FirewallOptions
import io.github.lucabarbalata.anprox.model.pve.FirewallRule
import io.github.lucabarbalata.anprox.model.pve.FirewallRuleForm
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.SecurityGroup
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

enum class FirewallTab { RULES, OPTIONS }

/** A rule being added ([rule] null) or edited. */
data class RuleEdit(val rule: FirewallRule?, val form: FirewallRuleForm)

data class FirewallState(
    val scope: FirewallScope,
    val tab: FirewallTab = FirewallTab.RULES,
    val loading: Boolean = true,
    val error: UiText? = null,
    val rules: List<FirewallRule> = emptyList(),
    val options: FirewallOptions? = null,
    /** Options changed on screen and not saved yet. */
    val editedOptions: Map<String, String> = emptyMap(),
    val macros: List<FirewallMacro> = emptyList(),
    val groups: List<SecurityGroup> = emptyList(),
    val editing: RuleEdit? = null,
    val confirmDelete: FirewallRule? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
) {
    /** An option's value as shown: edited, else saved, else Proxmox's default. */
    fun option(key: String): String = editedOptions[key] ?: options?.get(key) ?: OPTION_DEFAULTS[key].orEmpty()
    val optionsDirty: Boolean get() = options != null && options.changes(editedOptions).isNotEmpty()

    companion object {
        /** Proxmox's defaults for options that aren't set. */
        val OPTION_DEFAULTS = mapOf(
            "enable" to "0",
            "policy_in" to "DROP",
            "policy_out" to "ACCEPT",
            "log_level_in" to "nolog",
            "log_level_out" to "nolog",
            "dhcp" to "1",
            "ndp" to "1",
            "radv" to "0",
            "macfilter" to "1",
            "ipfilter" to "0",
        )
    }
}

/** Firewall rules and options of the datacenter, a node or a guest (questionnaire Q24). */
class FirewallViewModel(
    private val serverId: String,
    private val scope: FirewallScope,
    private val repo: FirewallRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(FirewallState(scope))
    val state: StateFlow<FirewallState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val rulesCall = async { repo.rules(serverId, scope) }
                    val optionsCall = async { repo.options(serverId, scope) }
                    // Macros and groups only help the rule editor; a server that hides them still works.
                    val macrosCall = async { optional { repo.macros(serverId) } }
                    val groupsCall = async { optional { repo.securityGroups(serverId) } }
                    val rules = rulesCall.await()
                    val options = optionsCall.await()
                    val macros = macrosCall.await()
                    val groups = groupsCall.await()
                    ui.update { it.copy(loading = false, rules = rules, options = options, editedOptions = emptyMap(), macros = macros, groups = groups) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    private suspend fun <T> optional(call: suspend () -> List<T>): List<T> = try {
        call()
    } catch (_: ApiException) {
        emptyList()
    }

    fun selectTab(tab: FirewallTab) {
        ui.update { it.copy(tab = tab) }
    }

    // ------------------------------------------------------------------ rules

    fun add() {
        ui.update { it.copy(editing = RuleEdit(null, FirewallRuleForm())) }
    }

    fun edit(rule: FirewallRule) {
        ui.update { it.copy(editing = RuleEdit(rule, FirewallRuleForm.from(rule))) }
    }

    fun editForm(change: FirewallRuleForm.() -> FirewallRuleForm) {
        ui.update { s -> s.copy(editing = s.editing?.let { it.copy(form = it.form.change()) }) }
    }

    fun closeEditor() {
        ui.update { it.copy(editing = null) }
    }

    fun saveRule() {
        val edit = ui.value.editing ?: return
        if (edit.form.problem() != null) return
        ui.update { it.copy(editing = null) }
        run(UiText.res(if (edit.rule == null) R.string.firewall_rule_added else R.string.firewall_rule_saved)) {
            if (edit.rule == null) repo.create(serverId, scope, edit.form) else repo.update(serverId, scope, edit.rule, edit.form)
        }
    }

    fun setEnabled(rule: FirewallRule, enabled: Boolean) {
        ui.update { s -> s.copy(rules = s.rules.map { if (it.pos == rule.pos) it.copy(enable = enabled) else it }) }
        run(null) { repo.setEnabled(serverId, scope, rule, enabled) }
    }

    fun move(rule: FirewallRule, by: Int) {
        val to = (rule.pos + by).coerceIn(0, ui.value.rules.lastIndex)
        if (to == rule.pos) return
        run(null) { repo.move(serverId, scope, rule, to) }
    }

    fun requestDelete(rule: FirewallRule) {
        ui.update { it.copy(editing = null, confirmDelete = rule) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = null) }
    }

    fun delete() {
        val rule = ui.value.confirmDelete ?: return
        ui.update { it.copy(confirmDelete = null) }
        run(UiText.res(R.string.firewall_rule_deleted)) { repo.delete(serverId, scope, rule) }
    }

    // ------------------------------------------------------------------ options

    fun setOption(key: String, value: String) {
        ui.update { it.copy(editedOptions = it.editedOptions + (key to value)) }
    }

    fun saveOptions() {
        val s = ui.value
        val original = s.options ?: return
        run(UiText.res(R.string.firewall_options_saved)) { repo.saveOptions(serverId, scope, original, s.editedOptions) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    /** Runs a change, then reloads (positions and the digest change with every edit). */
    private fun run(done: UiText?, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                done?.let { TaskResult(it, null, ok = true) }
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }
}
