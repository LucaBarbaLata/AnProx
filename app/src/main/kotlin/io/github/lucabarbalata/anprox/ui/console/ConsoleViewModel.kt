package io.github.lucabarbalata.anprox.ui.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.console.ConsoleRepository
import io.github.lucabarbalata.anprox.data.console.ConsoleSession
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConsoleState(
    val target: ConsoleTarget,
    val loading: Boolean = true,
    val session: ConsoleSession? = null,
    val error: UiText? = null,
    /** The page's own title, e.g. "web - Proxmox Console". */
    val pageTitle: String? = null,
    /** Bumped on reload so the WebView is created again. */
    val generation: Int = 0,
)

/** One of Proxmox's console pages in a WebView (questionnaire Q15). */
class ConsoleViewModel(
    private val serverId: String,
    private val target: ConsoleTarget,
    private val consoles: ConsoleRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(ConsoleState(target))
    val state: StateFlow<ConsoleState> = ui.asStateFlow()

    init {
        reload()
    }

    /** Fetches a fresh session (the ticket may have been renewed) and recreates the page. */
    fun reload() {
        ui.update { it.copy(loading = true, error = null, pageTitle = null) }
        viewModelScope.launch {
            try {
                val session = consoles.open(serverId, target)
                ui.update { it.copy(loading = false, session = session, generation = it.generation + 1) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun setPageTitle(title: String?) {
        ui.update { it.copy(pageTitle = title?.takeIf { t -> t.isNotBlank() && !t.startsWith("http") }) }
    }

    fun pageFailed(message: UiText) {
        ui.update { it.copy(error = message) }
    }
}
