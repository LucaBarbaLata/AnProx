package io.github.lucabarbalata.anprox.data.guest

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Identifies a VM or container on a specific saved server. */
data class GuestRef(val serverId: String, val node: String, val type: GuestType, val vmid: Int)

/** Result of polling something: the latest value and/or the latest error. */
data class Polled<T>(val value: T?, val error: ApiException?)

/** Guest-level operations (questionnaire Q18/Q19 grow this over the milestones). */
class GuestRepository(private val hub: ServerHub) {

    fun observeStatus(ref: GuestRef, intervalMillis: Long = 3_000): Flow<Polled<GuestStatus>> = poll(intervalMillis) {
        hub.use(ref.serverId) { it.pve { guestStatus(ref.node, ref.type.apiName, ref.vmid) } }
    }

    fun observeNode(serverId: String, node: String, intervalMillis: Long = 3_000): Flow<Polled<NodeStatus>> =
        poll(intervalMillis) { hub.use(serverId) { it.pve { nodeStatus(node) } } }

    /** Starts [action] and returns the task UPID. */
    suspend fun power(ref: GuestRef, action: PowerAction): String {
        require(action.appliesTo(ref.type)) { "$action is not available for ${ref.type}" }
        return hub.use(ref.serverId) {
            it.pve { guestPower(ref.node, ref.type.apiName, ref.vmid, action.apiPath, emptyMap()) }
        }
    }

    suspend fun awaitTask(serverId: String, node: String, upid: String): TaskStatus =
        hub.use(serverId) { it.awaitPveTask(node, upid) }

    suspend fun taskStatus(serverId: String, node: String, upid: String): TaskStatus =
        hub.use(serverId) { it.pve { taskStatus(node, upid) } }

    suspend fun taskLog(serverId: String, node: String, upid: String, start: Int = 0, limit: Int = 500): List<TaskLogLine> =
        hub.use(serverId) { it.pve { taskLog(node, upid, start, limit) } }

    private fun <T> poll(intervalMillis: Long, fetch: suspend () -> T): Flow<Polled<T>> = flow {
        var last: T? = null
        while (true) {
            try {
                last = fetch()
                emit(Polled(last, null))
            } catch (e: ApiException) {
                emit(Polled(last, e))
            }
            delay(intervalMillis)
        }
    }
}
