package io.github.lucabarbalata.anprox.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.EndpointUrl
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

/** One address being edited. Headers are edited as "Name: value" lines. */
data class EndpointDraft(
    val url: String = "",
    val label: String = "",
    val headers: String = "",
    val error: EndpointUrl.Reason? = null,
)

data class ServerEditorState(
    val isNew: Boolean = true,
    val loaded: Boolean = false,
    val name: String = "",
    val product: ProxmoxProduct = ProxmoxProduct.PVE,
    val endpoints: List<EndpointDraft> = listOf(EndpointDraft()),
    /** `user@realm`, e.g. `root@pam`. */
    val user: String = "root@pam",
    val allowInsecure: Boolean = false,
    val userError: Boolean = false,
    val saving: Boolean = false,
    /** Set once saved; the screen then moves on to the login screen. */
    val savedId: String? = null,
)

class ServerEditorViewModel(
    private val serverId: String?,
    private val hub: ServerHub,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerEditorState(isNew = serverId == null, loaded = serverId == null))
    val state: StateFlow<ServerEditorState> = _state.asStateFlow()

    private var existing: ServerProfile? = null

    init {
        if (serverId != null) {
            viewModelScope.launch {
                hub.awaitLoaded()
                val p = hub.server(serverId)?.profile ?: return@launch
                existing = p
                _state.value = ServerEditorState(
                    isNew = false,
                    loaded = true,
                    name = p.name,
                    product = p.product,
                    endpoints = p.endpoints.map { e ->
                        EndpointDraft(e.url, e.label.orEmpty(), e.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    },
                    user = p.userId,
                    allowInsecure = p.allowInsecureTls,
                )
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setUser(v: String) = _state.update { it.copy(user = v, userError = false) }
    fun setAllowInsecure(v: Boolean) = _state.update { it.copy(allowInsecure = v) }
    fun setProduct(v: ProxmoxProduct) = _state.update { it.copy(product = v) }

    fun updateEndpoint(index: Int, transform: (EndpointDraft) -> EndpointDraft) = _state.update { s ->
        s.copy(endpoints = s.endpoints.mapIndexed { i, e -> if (i == index) transform(e).copy(error = null) else e })
    }

    fun addEndpoint() = _state.update { it.copy(endpoints = it.endpoints + EndpointDraft()) }

    fun removeEndpoint(index: Int) = _state.update { s ->
        if (s.endpoints.size <= 1) s else s.copy(endpoints = s.endpoints.filterIndexed { i, _ -> i != index })
    }

    fun moveEndpointUp(index: Int) = _state.update { s ->
        if (index <= 0) s else s.copy(endpoints = s.endpoints.toMutableList().apply { add(index - 1, removeAt(index)) })
    }

    fun save() {
        val s = _state.value
        // Validate every address.
        val normalized = s.endpoints.map { EndpointUrl.normalize(it.url, s.product) }
        val withErrors = s.endpoints.zip(normalized) { draft, result ->
            draft.copy(error = (result as? EndpointUrl.Result.Invalid)?.reason)
        }
        val (username, realm) = parseUser(s.user)
        val userError = username.isBlank() || realm.isBlank()
        if (withErrors.any { it.error != null } || userError) {
            _state.update { it.copy(endpoints = withErrors, userError = userError) }
            return
        }
        val endpoints = withErrors.zip(normalized) { draft, result ->
            Endpoint(
                url = (result as EndpointUrl.Result.Ok).url,
                label = draft.label.trim().ifEmpty { null },
                headers = parseHeaders(draft.headers),
            )
        }
        val profile = ServerProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = s.name.trim().ifEmpty { hostOf(endpoints.first().url) },
            product = s.product,
            endpoints = endpoints,
            username = username,
            realm = realm,
            allowInsecureTls = s.allowInsecure,
            rememberPassword = existing?.rememberPassword ?: false,
            sortOrder = existing?.sortOrder ?: hub.servers.value.size,
        )
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            hub.save(profile)
            // Wait until the hub has picked the profile up, so the login screen finds it.
            hub.servers.first { list -> list.any { it.profile == profile } }
            _state.update { it.copy(saving = false, savedId = profile.id) }
        }
    }

    companion object {
        /** "root@pam" → ("root", "pam"); "root" → ("root", "pam"). Realm-less input defaults to PAM. */
        fun parseUser(input: String): Pair<String, String> {
            val t = input.trim()
            val at = t.lastIndexOf('@')
            return if (at < 0) t to "pam" else t.substring(0, at) to t.substring(at + 1)
        }

        /** "X-Token: abc\nX-Other: def" → map. Lines without ':' are ignored. */
        fun parseHeaders(text: String): Map<String, String> = text.lines()
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()

        private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
    }
}
