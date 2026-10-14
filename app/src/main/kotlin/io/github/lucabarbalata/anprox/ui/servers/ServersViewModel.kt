package io.github.lucabarbalata.anprox.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.hub.ServerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ServersViewModel(private val hub: ServerHub) : ViewModel() {
    val servers: StateFlow<List<ServerState>> = hub.servers

    fun logout(id: String) {
        viewModelScope.launch { hub.logout(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { hub.delete(id) }
    }
}
