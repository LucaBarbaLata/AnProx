package io.github.lucabarbalata.anprox.network.api

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ClusterStatusEntry
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Proxmox VE endpoints used so far. docs/api-coverage.md tracks every endpoint of the
 * official API and which milestone adds it here.
 */
interface PveApi {
    @GET("cluster/status")
    suspend fun clusterStatus(): ApiEnvelope<List<ClusterStatusEntry>>

    /** [type] narrows the result: `vm`, `node`, `storage`, `sdn`. `null` returns everything. */
    @GET("cluster/resources")
    suspend fun clusterResources(@Query("type") type: String?): ApiEnvelope<List<ClusterResource>>

    /** The most recent tasks of the whole cluster (what the web UI's bottom panel shows). */
    @GET("cluster/tasks")
    suspend fun clusterTasks(): ApiEnvelope<List<TaskSummary>>

    @GET("nodes/{node}/status")
    suspend fun nodeStatus(@Path("node") node: String): ApiEnvelope<NodeStatus>

    /** [type] is `qemu` or `lxc`. */
    @GET("nodes/{node}/{type}/{vmid}/status/current")
    suspend fun guestStatus(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
    ): ApiEnvelope<GuestStatus>

    /** [action] is a [io.github.lucabarbalata.anprox.model.pve.PowerAction.apiPath]. Returns a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/{type}/{vmid}/status/{action}")
    suspend fun guestPower(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @Path("action") action: String,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    @GET("nodes/{node}/tasks/{upid}/status")
    suspend fun taskStatus(@Path("node") node: String, @Path("upid") upid: String): ApiEnvelope<TaskStatus>

    @GET("nodes/{node}/tasks/{upid}/log")
    suspend fun taskLog(
        @Path("node") node: String,
        @Path("upid") upid: String,
        @Query("start") start: Int,
        @Query("limit") limit: Int,
    ): ApiEnvelope<List<TaskLogLine>>
}
