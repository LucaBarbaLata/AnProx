package io.github.lucabarbalata.anprox.network.api

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.pve.AgentInterface
import io.github.lucabarbalata.anprox.model.pve.AgentOsInfo
import io.github.lucabarbalata.anprox.model.pve.AgentResult
import io.github.lucabarbalata.anprox.model.pve.CephStatus
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ClusterStatusEntry
import io.github.lucabarbalata.anprox.model.pve.ConsoleProxy
import io.github.lucabarbalata.anprox.model.pve.FirewallMacro
import io.github.lucabarbalata.anprox.model.pve.FirewallRule
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.HaGroup
import io.github.lucabarbalata.anprox.model.pve.HaResource
import io.github.lucabarbalata.anprox.model.pve.HaStatusEntry
import io.github.lucabarbalata.anprox.model.pve.LxcInterface
import io.github.lucabarbalata.anprox.model.pve.MigrationCheck
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.NotBackedUp
import io.github.lucabarbalata.anprox.model.pve.PendingEntry
import io.github.lucabarbalata.anprox.model.pve.ResourcePool
import io.github.lucabarbalata.anprox.model.pve.RrdPoint
import io.github.lucabarbalata.anprox.model.pve.ScheduledRun
import io.github.lucabarbalata.anprox.model.pve.SecurityGroup
import io.github.lucabarbalata.anprox.model.pve.StorageConfig
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import io.github.lucabarbalata.anprox.model.pve.UrlMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Part
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

    /** Scheduled backup jobs; raw objects because their options vary between versions. */
    @GET("cluster/backup")
    suspend fun backupJobs(): ApiEnvelope<List<JsonObject>>

    @FormUrlEncoded
    @POST("cluster/backup")
    suspend fun createBackupJob(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("cluster/backup/{id}")
    suspend fun updateBackupJob(@Path("id") id: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("cluster/backup/{id}")
    suspend fun deleteBackupJob(@Path("id") id: String): ApiEnvelope<JsonElement>

    /** Guests that no enabled backup job covers. */
    @GET("cluster/backup-info/not-backed-up")
    suspend fun notBackedUp(): ApiEnvelope<List<NotBackedUp>>

    /** When a calendar event would run next; fails for a schedule Proxmox can't parse. */
    @GET("cluster/jobs/schedule-analyze")
    suspend fun analyzeSchedule(@Query("schedule") schedule: String, @Query("iterations") iterations: Int): ApiEnvelope<List<ScheduledRun>>

    /** Storages as configured for the whole cluster. */
    @GET("storage")
    suspend fun storageConfigs(): ApiEnvelope<List<StorageConfig>>

    @GET("pools")
    suspend fun pools(): ApiEnvelope<List<ResourcePool>>

    /** What a download URL points to (name, size), fetched by the node. */
    @GET("nodes/{node}/query-url-metadata")
    suspend fun queryUrlMetadata(
        @Path("node") node: String,
        @Query("url") url: String,
        @Query("verify-certificates") verifyCertificates: Int,
    ): ApiEnvelope<UrlMetadata>

    /** The node downloads a file into the storage. Returns a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/storage/{storage}/download-url")
    suspend fun downloadUrl(
        @Path("node") node: String,
        @Path("storage") storage: String,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Upload an ISO image or template. The `content` part must come before the file. Returns a UPID. */
    @Multipart
    @POST("nodes/{node}/storage/{storage}/upload")
    suspend fun upload(
        @Path("node") node: String,
        @Path("storage") storage: String,
        @Part("content") content: RequestBody,
        @Part file: MultipartBody.Part,
    ): ApiEnvelope<String>

    /** Deletes a volume. Recent versions answer with a UPID, older ones with nothing. */
    @DELETE("nodes/{node}/storage/{storage}/content/{volume}")
    suspend fun deleteVolume(
        @Path("node") node: String,
        @Path("storage") storage: String,
        @Path("volume") volume: String,
    ): ApiEnvelope<JsonElement>

    // Firewall (questionnaire Q24). [scope] is FirewallScope.path, e.g. `nodes/pve1/firewall`.

    @GET("{scope}/rules")
    suspend fun firewallRules(@Path(value = "scope", encoded = true) scope: String): ApiEnvelope<List<FirewallRule>>

    @FormUrlEncoded
    @POST("{scope}/rules")
    suspend fun createFirewallRule(@Path(value = "scope", encoded = true) scope: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** Edits a rule; `moveto` moves it to another position. */
    @FormUrlEncoded
    @PUT("{scope}/rules/{pos}")
    suspend fun updateFirewallRule(
        @Path(value = "scope", encoded = true) scope: String,
        @Path("pos") pos: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<JsonElement>

    @DELETE("{scope}/rules/{pos}")
    suspend fun deleteFirewallRule(
        @Path(value = "scope", encoded = true) scope: String,
        @Path("pos") pos: Int,
        @Query("digest") digest: String?,
    ): ApiEnvelope<JsonElement>

    @GET("{scope}/options")
    suspend fun firewallOptions(@Path(value = "scope", encoded = true) scope: String): ApiEnvelope<JsonObject>

    @FormUrlEncoded
    @PUT("{scope}/options")
    suspend fun updateFirewallOptions(@Path(value = "scope", encoded = true) scope: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @GET("cluster/firewall/macros")
    suspend fun firewallMacros(): ApiEnvelope<List<FirewallMacro>>

    @GET("cluster/firewall/groups")
    suspend fun securityGroups(): ApiEnvelope<List<SecurityGroup>>

    // High availability (questionnaire Q21)

    @GET("cluster/ha/resources")
    suspend fun haResources(): ApiEnvelope<List<HaResource>>

    @FormUrlEncoded
    @POST("cluster/ha/resources")
    suspend fun addHaResource(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("cluster/ha/resources/{sid}")
    suspend fun updateHaResource(@Path("sid") sid: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("cluster/ha/resources/{sid}")
    suspend fun removeHaResource(@Path("sid") sid: String): ApiEnvelope<JsonElement>

    /** Asks the HA manager to move a resource (live for running VMs). */
    @FormUrlEncoded
    @POST("cluster/ha/resources/{sid}/migrate")
    suspend fun migrateHaResource(@Path("sid") sid: String, @Field("node") node: String): ApiEnvelope<JsonElement>

    /** Proxmox VE 8; version 9 replaced groups with rules and may answer with an error. */
    @GET("cluster/ha/groups")
    suspend fun haGroups(): ApiEnvelope<List<HaGroup>>

    @GET("cluster/ha/status/current")
    suspend fun haStatus(): ApiEnvelope<List<HaStatusEntry>>

    // Storage replication (questionnaire Q21); raw objects because numbers come as text too.

    @GET("cluster/replication")
    suspend fun replicationJobs(): ApiEnvelope<List<JsonObject>>

    @FormUrlEncoded
    @POST("cluster/replication")
    suspend fun createReplicationJob(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("cluster/replication/{id}")
    suspend fun updateReplicationJob(@Path("id") id: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** Marks the job for removal; the next run deletes the copies on the target unless [keep] is 1. */
    @DELETE("cluster/replication/{id}")
    suspend fun deleteReplicationJob(@Path("id") id: String, @Query("keep") keep: Int?): ApiEnvelope<JsonElement>

    /** The runs of the jobs whose guests live on [node]. */
    @GET("nodes/{node}/replication")
    suspend fun replicationStatus(@Path("node") node: String): ApiEnvelope<List<JsonObject>>

    /** Runs the job as soon as possible (within a minute); no task to follow. */
    @POST("nodes/{node}/replication/{id}/schedule_now")
    suspend fun runReplicationNow(@Path("node") node: String, @Path("id") id: String): ApiEnvelope<JsonElement>

    /** The last run's log. */
    @GET("nodes/{node}/replication/{id}/log")
    suspend fun replicationLog(@Path("node") node: String, @Path("id") id: String, @Query("limit") limit: Int = 500): ApiEnvelope<List<TaskLogLine>>

    /** Ceph health; fails on a cluster without Ceph. */
    @GET("cluster/ceph/status")
    suspend fun cephStatus(): ApiEnvelope<CephStatus>

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

    /** Every config key with its current value and, if any, the change waiting for the next start. */
    @GET("nodes/{node}/{type}/{vmid}/pending")
    suspend fun guestPending(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
    ): ApiEnvelope<List<PendingEntry>>

    /** VMs only: the config change as a task (used for allocating disks). Answers a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/qemu/{vmid}/config")
    suspend fun updateVmConfigAsync(
        @Path("node") node: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Grows a disk (`disk`, `size` like `+10G`). A UPID on Proxmox VE 8 and for containers; older VMs answer null. */
    @FormUrlEncoded
    @PUT("nodes/{node}/{type}/{vmid}/resize")
    suspend fun resizeDisk(
        @Path("node") node: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Moves a VM disk to another storage (`disk`, `storage`, `delete`). Answers a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/qemu/{vmid}/move_disk")
    suspend fun moveVmDisk(
        @Path("node") node: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Moves a container volume to another storage (`volume`, `storage`, `delete`). Answers a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/lxc/{vmid}/move_volume")
    suspend fun moveCtVolume(
        @Path("node") node: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<String>

    /** Builds the VM's cloud-init image again with the current settings (`{"data": null}`). */
    @PUT("nodes/{node}/qemu/{vmid}/cloudinit")
    suspend fun regenerateCloudInit(@Path("node") node: String, @Path("vmid") vmid: Int): ApiEnvelope<JsonElement>

    /** A node's network interfaces; [type] `any_bridge` lists Linux and OVS bridges. */
    @GET("nodes/{node}/network")
    suspend fun nodeNetworks(@Path("node") node: String, @Query("type") type: String?): ApiEnvelope<List<NodeNetwork>>

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

    /** SPICE connection details for a running VM with a SPICE display; [params]: `proxy` (host). */
    @FormUrlEncoded
    @POST("nodes/{node}/qemu/{vmid}/spiceproxy")
    suspend fun spiceProxy(
        @Path("node") node: String,
        @Path("vmid") vmid: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<JsonObject>

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
