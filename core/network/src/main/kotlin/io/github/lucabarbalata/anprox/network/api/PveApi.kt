package io.github.lucabarbalata.anprox.network.api

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.pve.AgentInterface
import io.github.lucabarbalata.anprox.model.pve.AgentOsInfo
import io.github.lucabarbalata.anprox.model.pve.AgentResult
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ClusterStatusEntry
import io.github.lucabarbalata.anprox.model.pve.ConsoleProxy
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.LxcInterface
import io.github.lucabarbalata.anprox.model.pve.MigrationCheck
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.RrdPoint
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.PUT
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

    /** Reboots or shuts down a node ([command]: `reboot`, `shutdown`). Answers `null`; use with `pveNoData`. */
    @FormUrlEncoded
    @POST("nodes/{node}/status")
    suspend fun nodePower(@Path("node") node: String, @Field("command") command: String): ApiEnvelope<JsonElement>

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

    /** Performance history. [timeframe]: `hour` … `year`; [cf]: `AVERAGE` or `MAX`. */
    @GET("nodes/{node}/rrddata")
    suspend fun nodeRrd(
        @Path("node") node: String,
        @Query("timeframe") timeframe: String,
        @Query("cf") cf: String,
    ): ApiEnvelope<List<RrdPoint>>

    /** Performance history of a VM or container. */
    @GET("nodes/{node}/{type}/{vmid}/rrddata")
    suspend fun guestRrd(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @Query("timeframe") timeframe: String,
        @Query("cf") cf: String,
    ): ApiEnvelope<List<RrdPoint>>

    // ------------------------------------------------------------------ guest config (Q19)

    /** The guest's current configuration (keys differ between VMs and containers). */
    @GET("nodes/{node}/{type}/{vmid}/config")
    suspend fun guestConfig(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
    ): ApiEnvelope<JsonObject>

    /** Synchronous config change; answers `{"data": null}`. Use with `pveNoData`. */
    @FormUrlEncoded
    @PUT("nodes/{node}/{type}/{vmid}/config")
    suspend fun updateGuestConfig(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<JsonElement>

    // ------------------------------------------------------------------ guest agent (Q18)

    /** Fails with 500 when the agent isn't configured or not running. */
    @GET("nodes/{node}/qemu/{vmid}/agent/network-get-interfaces")
    suspend fun agentInterfaces(@Path("node") node: String, @Path("vmid") vmid: Int): ApiEnvelope<AgentResult<List<AgentInterface>>>

    @GET("nodes/{node}/qemu/{vmid}/agent/get-osinfo")
    suspend fun agentOsInfo(@Path("node") node: String, @Path("vmid") vmid: Int): ApiEnvelope<AgentResult<AgentOsInfo>>

    /** A running container's interfaces (PVE 7.2+); no agent needed. */
    @GET("nodes/{node}/lxc/{vmid}/interfaces")
    suspend fun lxcInterfaces(@Path("node") node: String, @Path("vmid") vmid: Int): ApiEnvelope<List<LxcInterface>>

    // ------------------------------------------------------------------ backups (Q20)

    /** Storages a node can use. [content] narrows it, e.g. `backup`; [enabled] 1 hides disabled ones. */
    @GET("nodes/{node}/storage")
    suspend fun nodeStorages(
        @Path("node") node: String,
        @Query("content") content: String?,
        @Query("enabled") enabled: Int?,
    ): ApiEnvelope<List<NodeStorage>>

    /** Volumes on a storage. [content] e.g. `backup`; [vmid] narrows to one guest. */
    @GET("nodes/{node}/storage/{storage}/content")
    suspend fun storageContent(
        @Path("node") node: String,
        @Path("storage") storage: String,
        @Query("content") content: String?,
        @Query("vmid") vmid: Int?,
    ): ApiEnvelope<List<StorageContentItem>>

    /** Backup now. [params]: `vmid`, `storage`, `mode`, optional `compress`, `notes-template`. Returns a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/vzdump")
    suspend fun vzdump(@Path("node") node: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    /** The lowest free VMID, for restoring or creating a guest. */
    @GET("cluster/nextid")
    suspend fun nextId(): ApiEnvelope<Int>

    /** Creates a VM; with `archive=<backup volid>` it restores one. Returns a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/qemu")
    suspend fun createQemu(@Path("node") node: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    /** Creates a container; with `ostemplate=<backup volid>` and `restore=1` it restores one. Returns a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/lxc")
    suspend fun createLxc(@Path("node") node: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    // ------------------------------------------------------------------ consoles (Q15)

    /**
     * `vncproxy` / `termproxy` at [path] (see `ConsoleTarget.proxyPath`): a one-time ticket and
     * port for the console WebSocket. VMs take `websocket=1` and `generate-password=1`.
     */
    @FormUrlEncoded
    @POST("{path}")
    suspend fun consoleProxy(
        @Path("path", encoded = true) path: String,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<ConsoleProxy>

    // ------------------------------------------------------------------ clone, template, migrate (Q18)

    /** [params]: `newid`, `name`/`hostname`, `target`, `full`, `storage`. Returns a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/{type}/{vmid}/clone")
    suspend fun cloneGuest(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Converts a stopped guest into a template. VMs answer with a UPID, containers with `null`. */
    @FormUrlEncoded
    @POST("nodes/{node}/{type}/{vmid}/template")
    suspend fun convertToTemplate(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Proxmox's pre-flight check for moving a VM: allowed nodes, local disks and devices. */
    @GET("nodes/{node}/qemu/{vmid}/migrate")
    suspend fun migrationCheck(@Path("node") node: String, @Path("vmid") vmid: Int): ApiEnvelope<MigrationCheck>

    /** [params]: `target`, plus `online`/`with-local-disks` (VMs) or `restart`/`timeout` (containers). Returns a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/{type}/{vmid}/migrate")
    suspend fun migrateGuest(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Snapshots, including the `current` pseudo-entry. [type] is `qemu` or `lxc`. */
    @GET("nodes/{node}/{type}/{vmid}/snapshot")
    suspend fun snapshots(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
    ): ApiEnvelope<List<GuestSnapshot>>

    /** [params]: `snapname`, optional `description`, and `vmstate=1` (VMs) to save the RAM. Returns a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/{type}/{vmid}/snapshot")
    suspend fun createSnapshot(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Returns a UPID. (A form body is required even when [params] is empty.) */
    @FormUrlEncoded
    @POST("nodes/{node}/{type}/{vmid}/snapshot/{snapname}/rollback")
    suspend fun rollbackSnapshot(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @Path("snapname") snapname: String,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Returns a UPID. */
    @DELETE("nodes/{node}/{type}/{vmid}/snapshot/{snapname}")
    suspend fun deleteSnapshot(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @Path("snapname") snapname: String,
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
