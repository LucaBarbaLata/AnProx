package io.github.lucabarbalata.anprox.network.api

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ClusterStatusEntry
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.RrdPoint
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import retrofit2.http.DELETE
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
