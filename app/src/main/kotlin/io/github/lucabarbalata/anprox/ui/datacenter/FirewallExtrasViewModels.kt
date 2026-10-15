package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.FirewallRepository
import io.github.lucabarbalata.anprox.model.pve.FirewallAlias
import io.github.lucabarbalata.anprox.model.pve.FirewallAliasForm
import io.github.lucabarbalata.anprox.model.pve.FirewallIpSet
import io.github.lucabarbalata.anprox.model.pve.FirewallNames
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.IpSetEntry
import io.github.lucabarbalata.anprox.model.pve.SecurityGroup
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a firewall offers besides its rules and options. */
enum class FirewallExtra { GROUPS, ALIASES, IPSETS, LOG }

/** A name and comment, for a new security group or IP set. */
data class NamedNew(val name: String = "", val comment: String = "") {
    val valid: Boolean get() = FirewallNames.isValidName(name.trim())
}

/** Loading, errors and one-at-a-time changes, shared by the screens below. */
abstract class FirewallObjectsViewModel<T>(initial: T) : ViewModel() {
    protected val data = MutableStateFlow(initial)
    val content: StateFlow<T> = data.asStateFlow()
    private val status = MutableStateFlow(Status())
    val state: StateFlow<Status> = status.asStateFlow()

    data class Status(val loading: Boolean = true, val error: UiText? = null, val busy: Boolean = false, val result: TaskResult? = null)

    /** Loads into [data]; subclasses call [load] from their own `init`, once their fields are set. */
    protected abstract suspend fun fetch()

    fun load() {
        status.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                fetch()
                status.update { it.copy(loading = false) }
            } catch (e: ApiException) {
                status.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    protected fun run(done: UiText, call: suspend () -> Unit) {
        if (status.value.busy) return
        status.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(done, null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            status.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    fun consumeResult() {
        status.update { it.copy(result = null) }
    }
}

// ------------------------------------------------------------------------------ security groups

data class GroupsContent(
    val groups: List<SecurityGroup> = emptyList(),
    val creating: NamedNew? = null,
    val confirmDelete: SecurityGroup? = null,
)

/** Datacenter → Firewall → Security groups. */
class SecurityGroupsViewModel(private val serverId: String, private val repo: FirewallRepository) : FirewallObjectsViewModel<GroupsContent>(GroupsContent()) {
    init {
        load()
    }

    override suspend fun fetch() {
        val groups = repo.securityGroups(serverId)
        data.update { it.copy(groups = groups) }
    }

    fun startCreate() = data.update { it.copy(creating = NamedNew()) }

    fun editNew(change: NamedNew.() -> NamedNew) = data.update { it.copy(creating = it.creating?.change()) }

    fun closeCreate() = data.update { it.copy(creating = null) }

    fun create() {
        val new = data.value.creating ?: return
        if (!new.valid) return
        data.update { it.copy(creating = null) }
        run(UiText.res(R.string.firewall_group_created, new.name.trim())) { repo.createGroup(serverId, new.name, new.comment) }
    }

    fun requestDelete(group: SecurityGroup) = data.update { it.copy(confirmDelete = group) }

    fun dismissDelete() = data.update { it.copy(confirmDelete = null) }

    fun delete() {
        val group = data.value.confirmDelete ?: return
        data.update { it.copy(confirmDelete = null) }
        run(UiText.res(R.string.firewall_group_deleted)) { repo.deleteGroup(serverId, group.group) }
    }
}

// ------------------------------------------------------------------------------ aliases

/** An alias being added ([alias] null) or edited. */
data class AliasEdit(val alias: FirewallAlias?, val form: FirewallAliasForm)

data class AliasesContent(val aliases: List<FirewallAlias> = emptyList(), val editing: AliasEdit? = null)

/** Aliases of the datacenter or a guest. */
class AliasesViewModel(
    private val serverId: String,
    private val scope: FirewallScope,
    private val repo: FirewallRepository,
) : FirewallObjectsViewModel<AliasesContent>(AliasesContent()) {
    init {
        load()
    }

    override suspend fun fetch() {
        val aliases = repo.aliases(serverId, scope)
        data.update { it.copy(aliases = aliases) }
    }

    fun add() = data.update { it.copy(editing = AliasEdit(null, FirewallAliasForm())) }

    fun edit(alias: FirewallAlias) = data.update { it.copy(editing = AliasEdit(alias, FirewallAliasForm.from(alias))) }

    fun editForm(change: FirewallAliasForm.() -> FirewallAliasForm) = data.update { s -> s.copy(editing = s.editing?.let { it.copy(form = it.form.change()) }) }

    fun close() = data.update { it.copy(editing = null) }

    fun save() {
        val edit = data.value.editing ?: return
        if (edit.form.problem() != null) return
        data.update { it.copy(editing = null) }
        val alias = edit.alias
        run(UiText.res(R.string.firewall_alias_saved)) {
            if (alias == null) repo.createAlias(serverId, scope, edit.form) else repo.updateAlias(serverId, scope, alias, edit.form)
        }
    }

    fun delete() {
        val alias = data.value.editing?.alias ?: return
        data.update { it.copy(editing = null) }
        run(UiText.res(R.string.firewall_alias_deleted)) { repo.deleteAlias(serverId, scope, alias) }
    }
}

// ------------------------------------------------------------------------------ IP sets

/** An address being added to [set]. */
data class NewEntry(val set: String, val cidr: String = "", val comment: String = "", val nomatch: Boolean = false) {
    val valid: Boolean get() = FirewallNames.isIpSetEntry(cidr.trim())
}

data class IpSetsContent(
    val sets: List<Pair<FirewallIpSet, List<IpSetEntry>>> = emptyList(),
    val creating: NamedNew? = null,
    val adding: NewEntry? = null,
    val confirmDelete: FirewallIpSet? = null,
)

/** IP sets of the datacenter or a guest. */
class IpSetsViewModel(
    private val serverId: String,
    private val scope: FirewallScope,
    private val repo: FirewallRepository,
) : FirewallObjectsViewModel<IpSetsContent>(IpSetsContent()) {
    init {
        load()
    }

    override suspend fun fetch() {
        val sets = repo.ipSets(serverId, scope)
        data.update { it.copy(sets = sets) }
    }

    fun startCreate() = data.update { it.copy(creating = NamedNew()) }

    fun editNew(change: NamedNew.() -> NamedNew) = data.update { it.copy(creating = it.creating?.change()) }

    fun closeCreate() = data.update { it.copy(creating = null) }

    fun create() {
        val new = data.value.creating ?: return
        if (!new.valid) return
        data.update { it.copy(creating = null) }
        run(UiText.res(R.string.firewall_ipset_created, new.name.trim())) { repo.createIpSet(serverId, scope, new.name, new.comment) }
    }

    fun startAdd(set: FirewallIpSet) = data.update { it.copy(adding = NewEntry(set.name)) }

    fun editEntry(change: NewEntry.() -> NewEntry) = data.update { it.copy(adding = it.adding?.change()) }

    fun closeAdd() = data.update { it.copy(adding = null) }

    fun addEntry() {
        val entry = data.value.adding ?: return
        if (!entry.valid) return
        data.update { it.copy(adding = null) }
        run(UiText.res(R.string.firewall_ipset_entry_added, entry.cidr.trim())) {
            repo.addIpSetEntry(serverId, scope, entry.set, entry.cidr, entry.comment, entry.nomatch)
        }
    }

    fun removeEntry(set: FirewallIpSet, entry: IpSetEntry) {
        run(UiText.res(R.string.firewall_ipset_entry_removed, entry.cidr)) { repo.removeIpSetEntry(serverId, scope, set.name, entry) }
    }

    fun requestDelete(set: FirewallIpSet) = data.update { it.copy(confirmDelete = set) }

    fun dismissDelete() = data.update { it.copy(confirmDelete = null) }

    fun delete() {
        val set = data.value.confirmDelete ?: return
        data.update { it.copy(confirmDelete = null) }
        run(UiText.res(R.string.firewall_ipset_deleted)) { repo.deleteIpSet(serverId, scope, set.name) }
    }
}

// ------------------------------------------------------------------------------ log

data class LogContent(val lines: List<TaskLogLine> = emptyList(), val filter: String = "") {
    val shown: List<TaskLogLine> get() = if (filter.isBlank()) lines else lines.filter { it.t.contains(filter.trim(), ignoreCase = true) }
}

/** The firewall log of a node or guest: the newest 500 lines. */
class FirewallLogViewModel(
    private val serverId: String,
    private val scope: FirewallScope,
    private val repo: FirewallRepository,
) : FirewallObjectsViewModel<LogContent>(LogContent()) {
    init {
        load()
    }

    override suspend fun fetch() {
        val lines = repo.log(serverId, scope)
        data.update { it.copy(lines = lines) }
    }

    fun setFilter(text: String) = data.update { it.copy(filter = text) }
}
