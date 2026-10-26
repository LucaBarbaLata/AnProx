package io.github.lucabarbalata.anprox.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.AccessRepository
import io.github.lucabarbalata.anprox.model.auth.Realm
import io.github.lucabarbalata.anprox.model.pve.AclEntry
import io.github.lucabarbalata.anprox.model.pve.AclForm
import io.github.lucabarbalata.anprox.model.pve.AclSubject
import io.github.lucabarbalata.anprox.model.pve.PveGroup
import io.github.lucabarbalata.anprox.model.pve.PveRole
import io.github.lucabarbalata.anprox.model.pve.PveUser
import io.github.lucabarbalata.anprox.model.pve.RealmSync
import io.github.lucabarbalata.anprox.model.pve.RealmSyncJob
import io.github.lucabarbalata.anprox.model.pve.RealmSyncJobForm
import io.github.lucabarbalata.anprox.model.pve.RealmType
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

// Datacenter → Permissions (milestone M7): the list screens. One user, the user editor and the
// realm editor have their own files.

data class UsersUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val users: List<PveUser> = emptyList(),
    /** A Backup Server has no groups. */
    val backupServer: Boolean = false,
)

/** Datacenter → Permissions → Users. */
class UsersViewModel(private val serverId: String, private val repo: AccessRepository) : ViewModel() {
    private val ui = MutableStateFlow(UsersUiState(backupServer = repo.isBackupServer(serverId)))
    val state: StateFlow<UsersUiState> = ui.asStateFlow()

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val users = repo.users(serverId)
                ui.update { it.copy(loading = false, users = users) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }
}

/** The "new group" and "edit group" dialog; [existing] is null while creating. */
data class GroupDraft(val existing: PveGroup?, val id: String = existing?.id.orEmpty(), val comment: String = existing?.comment.orEmpty()) {
    val valid: Boolean get() = PveGroup.ID.matches(id.trim())
}

data class GroupsUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val groups: List<PveGroup> = emptyList(),
    val draft: GroupDraft? = null,
    val confirmDelete: PveGroup? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
) {
    fun nameTaken(id: String): Boolean = groups.any { it.id == id.trim() }
}

/** Datacenter → Permissions → Groups: create, comment, delete. Members are set on the users. */
class GroupsViewModel(private val serverId: String, private val repo: AccessRepository) : ViewModel() {
    private val ui = MutableStateFlow(GroupsUiState())
    val state: StateFlow<GroupsUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val groups = repo.groups(serverId)
                ui.update { it.copy(loading = false, groups = groups) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun startCreate() {
        ui.update { it.copy(draft = GroupDraft(null)) }
    }

    fun startEdit(group: PveGroup) {
        ui.update { it.copy(draft = GroupDraft(group)) }
    }

    fun edit(change: GroupDraft.() -> GroupDraft) {
        ui.update { s -> s.copy(draft = s.draft?.change()) }
    }

    fun closeDraft() {
        ui.update { it.copy(draft = null) }
    }

    fun save() {
        val draft = ui.value.draft ?: return
        if (!draft.valid || (draft.existing == null && ui.value.nameTaken(draft.id))) return
        ui.update { it.copy(draft = null) }
        val id = draft.id.trim()
        if (draft.existing == null) {
            run(UiText.res(R.string.access_group_created, id)) { repo.createGroup(serverId, id, draft.comment) }
        } else {
            run(UiText.res(R.string.access_saved)) { repo.setGroupComment(serverId, id, draft.comment) }
        }
    }

    fun requestDelete(group: PveGroup) {
        ui.update { it.copy(draft = null, confirmDelete = group) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = null) }
    }

    fun delete() {
        val group = ui.value.confirmDelete ?: return
        ui.update { it.copy(confirmDelete = null) }
        run(UiText.res(R.string.access_deleted, group.id)) { repo.deleteGroup(serverId, group.id) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private fun run(done: UiText, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(done, null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }
}

/** The role dialog; [existing] is null while creating. Built-in roles open read-only. */
data class RoleDraft(val existing: PveRole?, val id: String = existing?.id.orEmpty(), val privileges: Set<String> = existing?.privileges.orEmpty()) {
    val readOnly: Boolean get() = existing?.builtIn == true
    val valid: Boolean get() = PveRole.ID.matches(id.trim()) && privileges.isNotEmpty()
}

data class RolesUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val roles: List<PveRole> = emptyList(),
    /** Every privilege the server knows. */
    val privileges: List<String> = emptyList(),
    val draft: RoleDraft? = null,
    val confirmDelete: PveRole? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
    /** A Backup Server's roles are fixed: none can be added. */
    val backupServer: Boolean = false,
) {
    fun nameTaken(id: String): Boolean = roles.any { it.id == id.trim() }
}

/** Datacenter → Permissions → Roles: the built-in ones to read, custom ones to create, edit and delete. */
class RolesViewModel(private val serverId: String, private val repo: AccessRepository) : ViewModel() {
    private val ui = MutableStateFlow(RolesUiState(backupServer = repo.isBackupServer(serverId)))
    val state: StateFlow<RolesUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val roles = repo.roles(serverId)
                ui.update { it.copy(loading = false, roles = roles, privileges = repo.privileges(roles)) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun startCreate() {
        ui.update { it.copy(draft = RoleDraft(null)) }
    }

    fun open(role: PveRole) {
        ui.update { it.copy(draft = RoleDraft(role)) }
    }

    fun changeId(id: String) {
        ui.update { s -> s.copy(draft = s.draft?.copy(id = id)) }
    }

    fun toggle(privilege: String) {
        ui.update { s ->
            val d = s.draft ?: return@update s
            if (d.readOnly) return@update s
            s.copy(draft = d.copy(privileges = if (privilege in d.privileges) d.privileges - privilege else d.privileges + privilege))
        }
    }

    fun closeDraft() {
        ui.update { it.copy(draft = null) }
    }

    fun save() {
        val draft = ui.value.draft ?: return
        if (draft.readOnly || !draft.valid || (draft.existing == null && ui.value.nameTaken(draft.id))) return
        ui.update { it.copy(draft = null) }
        val id = draft.id.trim()
        if (draft.existing == null) {
            run(UiText.res(R.string.access_role_created, id)) { repo.createRole(serverId, id, draft.privileges) }
        } else {
            run(UiText.res(R.string.access_saved)) { repo.updateRole(serverId, id, draft.privileges) }
        }
    }

    fun requestDelete(role: PveRole) {
        ui.update { it.copy(draft = null, confirmDelete = role) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = null) }
    }

    fun delete() {
        val role = ui.value.confirmDelete ?: return
        ui.update { it.copy(confirmDelete = null) }
        run(UiText.res(R.string.access_deleted, role.id)) { repo.deleteRole(serverId, role.id) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private fun run(done: UiText, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(done, null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }
}

data class AclUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val entries: List<AclEntry> = emptyList(),
    val users: List<PveUser> = emptyList(),
    val groups: List<PveGroup> = emptyList(),
    val roles: List<PveRole> = emptyList(),
    val adding: AclForm? = null,
    val confirmRemove: AclEntry? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
    /** On a Backup Server there are no groups, and the paths are different. */
    val backupServer: Boolean = false,
) {
    val subjects: List<AclSubject> get() = if (backupServer) AclSubject.entries - AclSubject.GROUP else AclSubject.entries
    val suggestedPaths: List<String> get() = if (backupServer) AclForm.PBS_PATHS else AclForm.SUGGESTED_PATHS

    /** The entries grouped by path, `/` first. */
    val byPath: List<Pair<String, List<AclEntry>>> get() = entries.groupBy { it.path }.toSortedMap().toList()

    /** Who [subject] can name: user IDs, group IDs or `user@realm!token`. */
    fun candidates(subject: AclSubject): List<String> = when (subject) {
        AclSubject.USER -> users.map { it.id }
        AclSubject.GROUP -> groups.map { it.id }
        AclSubject.TOKEN -> users.flatMap { u -> u.tokens.map { "${u.id}!${it.id}" } }
    }
}

/** Datacenter → Permissions → Permissions: who has which role where. */
class AclViewModel(private val serverId: String, private val repo: AccessRepository) : ViewModel() {
    private val ui = MutableStateFlow(AclUiState(backupServer = repo.isBackupServer(serverId)))
    val state: StateFlow<AclUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val aclCall = async { repo.acl(serverId) }
                    val usersCall = async { repo.users(serverId) }
                    val groupsCall = async { repo.groups(serverId) }
                    val rolesCall = async { repo.roles(serverId) }
                    val acl = aclCall.await()
                    val users = usersCall.await()
                    val groups = groupsCall.await()
                    val roles = rolesCall.await()
                    ui.update { it.copy(loading = false, entries = acl, users = users, groups = groups, roles = roles) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun startAdd() {
        ui.update { it.copy(adding = AclForm()) }
    }

    fun edit(change: AclForm.() -> AclForm) {
        ui.update { s -> s.copy(adding = s.adding?.change()) }
    }

    fun setSubject(subject: AclSubject) {
        // The name picked for another kind of subject doesn't fit this one.
        edit { if (this.subject == subject) this else copy(subject = subject, ugid = "") }
    }

    fun closeAdd() {
        ui.update { it.copy(adding = null) }
    }

    fun add() {
        val form = ui.value.adding ?: return
        if (form.problem() != null) return
        ui.update { it.copy(adding = null) }
        run(UiText.res(R.string.access_acl_added, form.role, form.ugid, form.path.trim())) { repo.addAcl(serverId, form) }
    }

    fun requestRemove(entry: AclEntry) {
        ui.update { it.copy(confirmRemove = entry) }
    }

    fun dismissRemove() {
        ui.update { it.copy(confirmRemove = null) }
    }

    fun remove() {
        val entry = ui.value.confirmRemove ?: return
        ui.update { it.copy(confirmRemove = null) }
        run(UiText.res(R.string.access_acl_removed)) { repo.removeAcl(serverId, entry) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private fun run(done: UiText, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(done, null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }
}

/** The directory sync dialog. */
data class SyncDraft(val realm: Realm, val sync: RealmSync = RealmSync())

/** A scheduled sync being added ([create]) or changed. */
data class SyncJobEdit(val form: RealmSyncJobForm, val create: Boolean)

data class RealmsUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val realms: List<Realm> = emptyList(),
    val syncing: SyncDraft? = null,
    val confirmDelete: Realm? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
    /** Scheduled syncs (empty for a user who may not read them). */
    val jobs: List<RealmSyncJob> = emptyList(),
    val jobEdit: SyncJobEdit? = null,
    val confirmDeleteJob: RealmSyncJob? = null,
) {
    /** The realms a directory sync works with: LDAP and Active Directory. */
    val syncable: List<Realm> get() = realms.filter { RealmType.fromApi(it.type).let { t -> t == RealmType.LDAP || t == RealmType.AD } }
}

/** Datacenter → Permissions → Realms: the list, directory sync and removing realms. Editing has its own screen. */
class RealmsViewModel(private val serverId: String, private val repo: AccessRepository) : ViewModel() {
    private val ui = MutableStateFlow(RealmsUiState())
    val state: StateFlow<RealmsUiState> = ui.asStateFlow()

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val realms = async { repo.realms(serverId) }
                    // Reading the jobs needs Sys.Audit; without it the section just stays empty.
                    val jobs = async {
                        try {
                            repo.syncJobs(serverId)
                        } catch (_: ApiException) {
                            emptyList()
                        }
                    }
                    ui.update { it.copy(loading = false, realms = realms.await(), jobs = jobs.await()) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun startAddJob() {
        val realm = ui.value.syncable.firstOrNull()?.realm ?: return
        ui.update { it.copy(jobEdit = SyncJobEdit(RealmSyncJobForm(realm = realm), create = true)) }
    }

    fun startEditJob(job: RealmSyncJob) {
        ui.update { it.copy(jobEdit = SyncJobEdit(RealmSyncJobForm.from(job), create = false)) }
    }

    fun editJob(change: RealmSyncJobForm.() -> RealmSyncJobForm) {
        ui.update { s -> s.copy(jobEdit = s.jobEdit?.let { it.copy(form = it.form.change()) }) }
    }

    fun cancelJob() {
        ui.update { it.copy(jobEdit = null) }
    }

    fun saveJob() {
        val edit = ui.value.jobEdit ?: return
        if (ui.value.busy || edit.form.problem() != null) return
        ui.update { it.copy(jobEdit = null, busy = true) }
        viewModelScope.launch {
            val result = try {
                if (edit.create) repo.createSyncJob(serverId, edit.form) else repo.updateSyncJob(serverId, edit.form)
                TaskResult(UiText.res(if (edit.create) R.string.access_sync_job_added else R.string.access_sync_job_saved), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    fun requestDeleteJob(job: RealmSyncJob) {
        ui.update { it.copy(confirmDeleteJob = job) }
    }

    fun dismissDeleteJob() {
        ui.update { it.copy(confirmDeleteJob = null) }
    }

    fun deleteJob() {
        val job = ui.value.confirmDeleteJob ?: return
        if (ui.value.busy) return
        ui.update { it.copy(confirmDeleteJob = null, busy = true) }
        viewModelScope.launch {
            val result = try {
                repo.deleteSyncJob(serverId, job.id)
                TaskResult(UiText.res(R.string.access_sync_job_deleted), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    fun startSync(realm: Realm) {
        ui.update { it.copy(syncing = SyncDraft(realm)) }
    }

    fun editSync(change: RealmSync.() -> RealmSync) {
        ui.update { s -> s.copy(syncing = s.syncing?.let { d -> d.copy(sync = d.sync.change()) }) }
    }

    fun closeSync() {
        ui.update { it.copy(syncing = null) }
    }

    fun sync() {
        val draft = ui.value.syncing ?: return
        if (ui.value.busy) return
        ui.update { it.copy(syncing = null, busy = true) }
        viewModelScope.launch {
            val result = try {
                val upid = repo.syncRealm(serverId, draft.realm.realm, draft.sync)
                val message = if (draft.sync.dryRun) R.string.access_sync_dry_started else R.string.access_sync_started
                TaskResult(UiText.res(message, draft.realm.realm), upid, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
        }
    }

    fun requestDelete(realm: Realm) {
        ui.update { it.copy(confirmDelete = realm) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = null) }
    }

    fun delete() {
        val realm = ui.value.confirmDelete ?: return
        if (ui.value.busy) return
        ui.update { it.copy(confirmDelete = null, busy = true) }
        viewModelScope.launch {
            val result = try {
                repo.deleteRealm(serverId, realm.realm)
                TaskResult(UiText.res(R.string.access_deleted, realm.realm), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}
