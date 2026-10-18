package io.github.lucabarbalata.anprox.network.api

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.pve.AgentInterface
import io.github.lucabarbalata.anprox.model.pve.AgentOsInfo
import io.github.lucabarbalata.anprox.model.pve.AgentResult
import io.github.lucabarbalata.anprox.model.pve.AptRepositories
import io.github.lucabarbalata.anprox.model.pve.AptUpdate
import io.github.lucabarbalata.anprox.model.pve.CephStatus
import io.github.lucabarbalata.anprox.model.pve.CifsShare
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ClusterStatusEntry
import io.github.lucabarbalata.anprox.model.pve.ConsoleProxy
import io.github.lucabarbalata.anprox.model.pve.FirewallAlias
import io.github.lucabarbalata.anprox.model.pve.FirewallIpSet
import io.github.lucabarbalata.anprox.model.pve.FirewallMacro
import io.github.lucabarbalata.anprox.model.pve.FirewallRule
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.HaGroup
import io.github.lucabarbalata.anprox.model.pve.HaResource
import io.github.lucabarbalata.anprox.model.pve.HaRule
import io.github.lucabarbalata.anprox.model.pve.HaStatusEntry
import io.github.lucabarbalata.anprox.model.pve.IpSetEntry
import io.github.lucabarbalata.anprox.model.pve.LvmThinPool
import io.github.lucabarbalata.anprox.model.pve.LvmVolumeGroup
import io.github.lucabarbalata.anprox.model.pve.LxcInterface
import io.github.lucabarbalata.anprox.model.pve.MigrationCheck
import io.github.lucabarbalata.anprox.model.pve.NfsExport
import io.github.lucabarbalata.anprox.model.pve.NodeDisk
import io.github.lucabarbalata.anprox.model.pve.NodeDns
import io.github.lucabarbalata.anprox.model.pve.NodeHosts
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeService
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.NodeSubscription
import io.github.lucabarbalata.anprox.model.pve.NodeTime
import io.github.lucabarbalata.anprox.model.pve.NotBackedUp
import io.github.lucabarbalata.anprox.model.pve.PackageVersion
import io.github.lucabarbalata.anprox.model.pve.PbsDatastore
import io.github.lucabarbalata.anprox.model.pve.PendingEntry
import io.github.lucabarbalata.anprox.model.pve.PoolDetail
import io.github.lucabarbalata.anprox.model.pve.ResourcePool
import io.github.lucabarbalata.anprox.model.pve.RrdPoint
import io.github.lucabarbalata.anprox.model.pve.ScheduledRun
import io.github.lucabarbalata.anprox.model.pve.SecurityGroup
import io.github.lucabarbalata.anprox.model.pve.SmartData
import io.github.lucabarbalata.anprox.model.pve.StorageConfig
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import io.github.lucabarbalata.anprox.model.pve.UrlMetadata
import io.github.lucabarbalata.anprox.model.pve.ZfsPoolName
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
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

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

    // Storage configuration (Datacenter → Storage)

    /** One storage's configuration as flat values, with its digest. */
    @GET("storage/{storage}")
    suspend fun storageConfig(@Path("storage") storage: String): ApiEnvelope<JsonObject>

    @FormUrlEncoded
    @POST("storage")
    suspend fun createStorage(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("storage/{storage}")
    suspend fun updateStorage(@Path("storage") storage: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** Removes the storage from the configuration; the data on it stays. */
    @DELETE("storage/{storage}")
    suspend fun deleteStorage(@Path("storage") storage: String): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/scan/nfs")
    suspend fun scanNfs(@Path("node") node: String, @Query("server") server: String): ApiEnvelope<List<NfsExport>>

    /** [params]: `server`, and `username`, `password`, `domain` when the server needs them. */
    @GET("nodes/{node}/scan/cifs")
    suspend fun scanCifs(@Path("node") node: String, @QueryMap params: Map<String, String>): ApiEnvelope<List<CifsShare>>

    /** [params]: `server`, `username`, `password`, and `fingerprint` for a self-signed certificate. */
    @GET("nodes/{node}/scan/pbs")
    suspend fun scanPbs(@Path("node") node: String, @QueryMap params: Map<String, String>): ApiEnvelope<List<PbsDatastore>>

    @GET("nodes/{node}/scan/lvm")
    suspend fun scanLvm(@Path("node") node: String): ApiEnvelope<List<LvmVolumeGroup>>

    @GET("nodes/{node}/scan/lvmthin")
    suspend fun scanLvmThin(@Path("node") node: String, @Query("vg") vg: String): ApiEnvelope<List<LvmThinPool>>

    @GET("nodes/{node}/scan/zfs")
    suspend fun scanZfs(@Path("node") node: String): ApiEnvelope<List<ZfsPoolName>>

    // Resource pools. Proxmox VE 8.1 moved them to `/pools?poolid=…` (pool names may then contain
    // slashes); the `/pools/{poolid}` forms are for older versions.

    @GET("pools")
    suspend fun poolDetails(@Query("poolid") id: String): ApiEnvelope<List<PoolDetail>>

    @GET("pools/{poolid}")
    suspend fun poolDetailLegacy(@Path("poolid") id: String): ApiEnvelope<PoolDetail>

    @FormUrlEncoded
    @POST("pools")
    suspend fun createPool(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** [params] include `poolid`. */
    @FormUrlEncoded
    @PUT("pools")
    suspend fun updatePool(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("pools/{poolid}")
    suspend fun updatePoolLegacy(@Path("poolid") id: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** Only an empty pool can be deleted. */
    @DELETE("pools")
    suspend fun deletePool(@Query("poolid") id: String): ApiEnvelope<JsonElement>

    @DELETE("pools/{poolid}")
    suspend fun deletePoolLegacy(@Path("poolid") id: String): ApiEnvelope<JsonElement>

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

    // Firewall (questionnaire Q24). [scope] is FirewallScope.path, e.g. `nodes/pve1/firewall`;
    // [rules] is FirewallScope.rulesPath (a security group lists its rules at its own path).

    @GET("{rules}")
    suspend fun firewallRules(@Path(value = "rules", encoded = true) rules: String): ApiEnvelope<List<FirewallRule>>

    @FormUrlEncoded
    @POST("{rules}")
    suspend fun createFirewallRule(@Path(value = "rules", encoded = true) rules: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** Edits a rule; `moveto` moves it to another position. */
    @FormUrlEncoded
    @PUT("{rules}/{pos}")
    suspend fun updateFirewallRule(
        @Path(value = "rules", encoded = true) rules: String,
        @Path("pos") pos: Int,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<JsonElement>

    @DELETE("{rules}/{pos}")
    suspend fun deleteFirewallRule(
        @Path(value = "rules", encoded = true) rules: String,
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

    /** [params]: `group`, `comment`. */
    @FormUrlEncoded
    @POST("cluster/firewall/groups")
    suspend fun createSecurityGroup(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** Only a group without rules can be deleted. */
    @DELETE("cluster/firewall/groups/{group}")
    suspend fun deleteSecurityGroup(@Path("group") group: String): ApiEnvelope<JsonElement>

    @GET("{scope}/aliases")
    suspend fun firewallAliases(@Path(value = "scope", encoded = true) scope: String): ApiEnvelope<List<FirewallAlias>>

    @FormUrlEncoded
    @POST("{scope}/aliases")
    suspend fun createFirewallAlias(@Path(value = "scope", encoded = true) scope: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("{scope}/aliases/{name}")
    suspend fun updateFirewallAlias(
        @Path(value = "scope", encoded = true) scope: String,
        @Path("name") name: String,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<JsonElement>

    @DELETE("{scope}/aliases/{name}")
    suspend fun deleteFirewallAlias(
        @Path(value = "scope", encoded = true) scope: String,
        @Path("name") name: String,
        @Query("digest") digest: String?,
    ): ApiEnvelope<JsonElement>

    @GET("{scope}/ipset")
    suspend fun firewallIpSets(@Path(value = "scope", encoded = true) scope: String): ApiEnvelope<List<FirewallIpSet>>

    /** [params]: `name`, `comment`. */
    @FormUrlEncoded
    @POST("{scope}/ipset")
    suspend fun createFirewallIpSet(@Path(value = "scope", encoded = true) scope: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** [force] 1 deletes the set with its entries. */
    @DELETE("{scope}/ipset/{name}")
    suspend fun deleteFirewallIpSet(
        @Path(value = "scope", encoded = true) scope: String,
        @Path("name") name: String,
        @Query("force") force: Int?,
    ): ApiEnvelope<JsonElement>

    @GET("{scope}/ipset/{name}")
    suspend fun firewallIpSetEntries(@Path(value = "scope", encoded = true) scope: String, @Path("name") name: String): ApiEnvelope<List<IpSetEntry>>

    /** [params]: `cidr`, `comment`, `nomatch`. */
    @FormUrlEncoded
    @POST("{scope}/ipset/{name}")
    suspend fun addIpSetEntry(
        @Path(value = "scope", encoded = true) scope: String,
        @Path("name") name: String,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<JsonElement>

    /** [cidr] is path-encoded (`10.0.0.0%2F24`). */
    @DELETE("{scope}/ipset/{name}/{cidr}")
    suspend fun deleteIpSetEntry(
        @Path(value = "scope", encoded = true) scope: String,
        @Path("name") name: String,
        @Path("cidr") cidr: String,
        @Query("digest") digest: String?,
    ): ApiEnvelope<JsonElement>

    /** Lines [start] … of the firewall log of a node or guest; the envelope's `total` counts them all. */
    @GET("{scope}/log")
    suspend fun firewallLog(
        @Path(value = "scope", encoded = true) scope: String,
        @Query("start") start: Int,
        @Query("limit") limit: Int,
    ): ApiEnvelope<List<TaskLogLine>>

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

    @FormUrlEncoded
    @POST("cluster/ha/groups")
    suspend fun createHaGroup(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("cluster/ha/groups/{group}")
    suspend fun updateHaGroup(@Path("group") group: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("cluster/ha/groups/{group}")
    suspend fun deleteHaGroup(@Path("group") group: String): ApiEnvelope<JsonElement>

    /** Proxmox VE 9; older versions answer with an error. */
    @GET("cluster/ha/rules")
    suspend fun haRules(): ApiEnvelope<List<HaRule>>

    @FormUrlEncoded
    @POST("cluster/ha/rules")
    suspend fun createHaRule(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("cluster/ha/rules/{rule}")
    suspend fun updateHaRule(@Path("rule") rule: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("cluster/ha/rules/{rule}")
    suspend fun deleteHaRule(@Path("rule") rule: String): ApiEnvelope<JsonElement>

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

    // ------------------------------------------------------------------ node administration (Q22)

    @GET("nodes/{node}/apt/update")
    suspend fun aptUpdates(@Path("node") node: String): ApiEnvelope<List<AptUpdate>>

    /** `apt-get update` as a task; returns its UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/apt/update")
    suspend fun refreshAptIndex(@Path("node") node: String, @Field("notify") notify: Int = 0): ApiEnvelope<String>

    /** The package's changelog as plain text. */
    @GET("nodes/{node}/apt/changelog")
    suspend fun aptChangelog(@Path("node") node: String, @Query("name") name: String, @Query("version") version: String?): ApiEnvelope<String>

    @GET("nodes/{node}/apt/versions")
    suspend fun packageVersions(@Path("node") node: String): ApiEnvelope<List<PackageVersion>>

    @GET("nodes/{node}/apt/repositories")
    suspend fun aptRepositories(@Path("node") node: String): ApiEnvelope<AptRepositories>

    /** Switches the repository at [index] of [path] on or off. */
    @FormUrlEncoded
    @POST("nodes/{node}/apt/repositories")
    suspend fun setAptRepositoryEnabled(
        @Path("node") node: String,
        @Field("path") path: String,
        @Field("index") index: Int,
        @Field("enabled") enabled: Int,
        @Field("digest") digest: String?,
    ): ApiEnvelope<JsonElement>

    /** Adds a standard repository ([handle]: `no-subscription`, `enterprise`, `test`, …). */
    @FormUrlEncoded
    @PUT("nodes/{node}/apt/repositories")
    suspend fun addStandardAptRepository(@Path("node") node: String, @Field("handle") handle: String, @Field("digest") digest: String?): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/subscription")
    suspend fun subscription(@Path("node") node: String): ApiEnvelope<NodeSubscription>

    /** Sets the subscription key (Proxmox checks it online). */
    @FormUrlEncoded
    @PUT("nodes/{node}/subscription")
    suspend fun setSubscriptionKey(@Path("node") node: String, @Field("key") key: String): ApiEnvelope<JsonElement>

    @DELETE("nodes/{node}/subscription")
    suspend fun removeSubscriptionKey(@Path("node") node: String): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/services")
    suspend fun services(@Path("node") node: String): ApiEnvelope<List<NodeService>>

    /** [action]: `start`, `stop`, `restart`, `reload`. Returns a UPID. */
    @POST("nodes/{node}/services/{service}/{action}")
    suspend fun serviceAction(@Path("node") node: String, @Path("service") service: String, @Path("action") action: String): ApiEnvelope<String>

    @FormUrlEncoded
    @POST("nodes/{node}/network")
    suspend fun createNetwork(@Path("node") node: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("nodes/{node}/network/{iface}")
    suspend fun updateNetwork(@Path("node") node: String, @Path("iface") iface: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("nodes/{node}/network/{iface}")
    suspend fun deleteNetwork(@Path("node") node: String, @Path("iface") iface: String): ApiEnvelope<JsonElement>

    /** Applies the pending configuration (ifreload); returns a UPID. */
    @PUT("nodes/{node}/network")
    suspend fun applyNetwork(@Path("node") node: String): ApiEnvelope<String>

    /** Drops the pending configuration. */
    @DELETE("nodes/{node}/network")
    suspend fun revertNetwork(@Path("node") node: String): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/disks/list")
    suspend fun disks(@Path("node") node: String, @Query("include-partitions") partitions: Int = 0): ApiEnvelope<List<NodeDisk>>

    @GET("nodes/{node}/disks/smart")
    suspend fun smart(@Path("node") node: String, @Query("disk") disk: String): ApiEnvelope<SmartData>

    /** Wipes the partition table and signatures; returns a UPID. */
    @FormUrlEncoded
    @PUT("nodes/{node}/disks/wipedisk")
    suspend fun wipeDisk(@Path("node") node: String, @Field("disk") disk: String): ApiEnvelope<String>

    @FormUrlEncoded
    @POST("nodes/{node}/disks/initgpt")
    suspend fun initGpt(@Path("node") node: String, @Field("disk") disk: String): ApiEnvelope<String>

    /** [kind]: `directory`, `lvm`, `lvmthin`, `zfs`. Returns a UPID. */
    @FormUrlEncoded
    @POST("nodes/{node}/disks/{kind}")
    suspend fun createDiskStorage(@Path("node") node: String, @Path("kind") kind: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    @GET("nodes/{node}/dns")
    suspend fun nodeDns(@Path("node") node: String): ApiEnvelope<NodeDns>

    @FormUrlEncoded
    @PUT("nodes/{node}/dns")
    suspend fun setNodeDns(@Path("node") node: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/hosts")
    suspend fun nodeHosts(@Path("node") node: String): ApiEnvelope<NodeHosts>

    @FormUrlEncoded
    @POST("nodes/{node}/hosts")
    suspend fun setNodeHosts(@Path("node") node: String, @Field("data") data: String, @Field("digest") digest: String?): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/time")
    suspend fun nodeTime(@Path("node") node: String): ApiEnvelope<NodeTime>

    @FormUrlEncoded
    @PUT("nodes/{node}/time")
    suspend fun setNodeTimezone(@Path("node") node: String, @Field("timezone") timezone: String): ApiEnvelope<JsonElement>

    /** The newest [lastEntries] journal lines, between `-- cursor:` markers. */
    @GET("nodes/{node}/journal")
    suspend fun journal(@Path("node") node: String, @Query("lastentries") lastEntries: Int): ApiEnvelope<List<String>>

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

    // ------------------------------------------------------------------ access control (Q23, M7)

    @GET("access/users")
    suspend fun users(@Query("full") full: Int = 1): ApiEnvelope<JsonElement>

    @GET("access/users/{userid}")
    suspend fun user(@Path("userid") userId: String): ApiEnvelope<JsonObject>

    @FormUrlEncoded
    @POST("access/users")
    suspend fun createUser(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("access/users/{userid}")
    suspend fun updateUser(@Path("userid") userId: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("access/users/{userid}")
    suspend fun deleteUser(@Path("userid") userId: String): ApiEnvelope<JsonElement>

    /** [params]: `userid`, `password`, and `confirmation-password` (the caller's own) on PVE 8.1+. */
    @FormUrlEncoded
    @PUT("access/password")
    suspend fun changePassword(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** Lifts a TOTP/TFA lockout after too many wrong codes (PVE 7.3+). */
    @PUT("access/users/{userid}/unlock-tfa")
    suspend fun unlockTfa(@Path("userid") userId: String): ApiEnvelope<JsonElement>

    @GET("access/users/{userid}/token")
    suspend fun tokens(@Path("userid") userId: String): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("access/users/{userid}/token/{tokenid}")
    suspend fun createToken(
        @Path("userid") userId: String,
        @Path("tokenid") tokenId: String,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<JsonObject>

    @DELETE("access/users/{userid}/token/{tokenid}")
    suspend fun deleteToken(@Path("userid") userId: String, @Path("tokenid") tokenId: String): ApiEnvelope<JsonElement>

    @GET("access/groups")
    suspend fun groups(): ApiEnvelope<JsonElement>

    @GET("access/groups/{groupid}")
    suspend fun group(@Path("groupid") id: String): ApiEnvelope<JsonObject>

    @FormUrlEncoded
    @POST("access/groups")
    suspend fun createGroup(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("access/groups/{groupid}")
    suspend fun updateGroup(@Path("groupid") id: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("access/groups/{groupid}")
    suspend fun deleteGroup(@Path("groupid") id: String): ApiEnvelope<JsonElement>

    @GET("access/roles")
    suspend fun roles(): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("access/roles")
    suspend fun createRole(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("access/roles/{roleid}")
    suspend fun updateRole(@Path("roleid") id: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("access/roles/{roleid}")
    suspend fun deleteRole(@Path("roleid") id: String): ApiEnvelope<JsonElement>

    @GET("access/acl")
    suspend fun acl(): ApiEnvelope<JsonElement>

    /** Adds (or with `delete=1` removes) `roles` for `users`/`groups`/`tokens` on `path`. */
    @FormUrlEncoded
    @PUT("access/acl")
    suspend fun updateAcl(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @GET("access/domains/{realm}")
    suspend fun realmConfig(@Path("realm") realm: String): ApiEnvelope<JsonObject>

    @FormUrlEncoded
    @POST("access/domains")
    suspend fun createRealm(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("access/domains/{realm}")
    suspend fun updateRealm(@Path("realm") realm: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("access/domains/{realm}")
    suspend fun deleteRealm(@Path("realm") realm: String): ApiEnvelope<JsonElement>

    /** Syncs users and groups from an LDAP/AD realm. Returns a UPID. */
    @FormUrlEncoded
    @POST("access/domains/{realm}/sync")
    suspend fun syncRealm(@Path("realm") realm: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    @GET("access/tfa")
    suspend fun tfa(): ApiEnvelope<JsonElement>

    /** Adds a factor; answers its id, and the keys for `type=recovery`. */
    @FormUrlEncoded
    @POST("access/tfa/{userid}")
    suspend fun addTfa(@Path("userid") userId: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonObject>

    @FormUrlEncoded
    @PUT("access/tfa/{userid}/{id}")
    suspend fun updateTfa(
        @Path("userid") userId: String,
        @Path("id") id: String,
        @FieldMap params: Map<String, String>,
    ): ApiEnvelope<JsonElement>

    @DELETE("access/tfa/{userid}/{id}")
    suspend fun deleteTfa(
        @Path("userid") userId: String,
        @Path("id") id: String,
        @Query("password") password: String?,
    ): ApiEnvelope<JsonElement>

    @GET("access/permissions")
    suspend fun permissions(@Query("userid") userId: String?, @Query("path") path: String?): ApiEnvelope<JsonObject>
    // ---- Ceph (M6). Most calls run on any node with Ceph; destroying an OSD or a service runs on its host.

    @GET("nodes/{node}/ceph/status")
    suspend fun cephOverview(@Path("node") node: String): ApiEnvelope<JsonObject>

    @GET("nodes/{node}/ceph/osd")
    suspend fun cephOsds(@Path("node") node: String): ApiEnvelope<JsonObject>

    @FormUrlEncoded
    @POST("nodes/{node}/ceph/osd")
    suspend fun createCephOsd(@Path("node") node: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    @DELETE("nodes/{node}/ceph/osd/{id}")
    suspend fun destroyCephOsd(@Path("node") node: String, @Path("id") id: Int, @Query("cleanup") cleanup: Int): ApiEnvelope<String>

    @POST("nodes/{node}/ceph/osd/{id}/in")
    suspend fun cephOsdIn(@Path("node") node: String, @Path("id") id: Int): ApiEnvelope<JsonElement>

    @POST("nodes/{node}/ceph/osd/{id}/out")
    suspend fun cephOsdOut(@Path("node") node: String, @Path("id") id: Int): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("nodes/{node}/ceph/osd/{id}/scrub")
    suspend fun cephOsdScrub(@Path("node") node: String, @Path("id") id: Int, @Field("deep") deep: Int): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/ceph/pool")
    suspend fun cephPools(@Path("node") node: String): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("nodes/{node}/ceph/pool")
    suspend fun createCephPool(@Path("node") node: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    @FormUrlEncoded
    @PUT("nodes/{node}/ceph/pool/{name}")
    suspend fun updateCephPool(@Path("node") node: String, @Path("name") name: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    @DELETE("nodes/{node}/ceph/pool/{name}")
    suspend fun deleteCephPool(@Path("node") node: String, @Path("name") name: String, @Query("remove_storages") removeStorages: Int): ApiEnvelope<String>

    @GET("nodes/{node}/ceph/rules")
    suspend fun cephRules(@Path("node") node: String): ApiEnvelope<JsonElement>

    /** [type] is `mon`, `mgr` or `mds`. */
    @GET("nodes/{node}/ceph/{type}")
    suspend fun cephServices(@Path("node") node: String, @Path("type") type: String): ApiEnvelope<JsonElement>

    /** Creates a monitor, manager or metadata server on [node], named after it. */
    @POST("nodes/{node}/ceph/{type}/{name}")
    suspend fun createCephService(@Path("node") node: String, @Path("type") type: String, @Path("name") name: String): ApiEnvelope<String>

    @DELETE("nodes/{node}/ceph/{type}/{name}")
    suspend fun destroyCephService(@Path("node") node: String, @Path("type") type: String, @Path("name") name: String): ApiEnvelope<String>

    /** [action] is `start`, `stop` or `restart`; [service] e.g. `osd.3` or `mon.pve1`. */
    @FormUrlEncoded
    @POST("nodes/{node}/ceph/{action}")
    suspend fun cephServiceAction(@Path("node") node: String, @Path("action") action: String, @Field("service") service: String): ApiEnvelope<String>

    @GET("nodes/{node}/ceph/fs")
    suspend fun cephFs(@Path("node") node: String): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("nodes/{node}/ceph/fs/{name}")
    suspend fun createCephFs(@Path("node") node: String, @Path("name") name: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    @GET("cluster/ceph/flags")
    suspend fun cephFlags(): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("cluster/ceph/flags/{flag}")
    suspend fun setCephFlag(@Path("flag") flag: String, @Field("value") value: Int): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/ceph/cfg/raw")
    suspend fun cephConfig(@Path("node") node: String): ApiEnvelope<String>

    @GET("nodes/{node}/ceph/cfg/db")
    suspend fun cephConfigDb(@Path("node") node: String): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/ceph/crush")
    suspend fun cephCrush(@Path("node") node: String): ApiEnvelope<String>

    @GET("nodes/{node}/ceph/log")
    suspend fun cephLog(@Path("node") node: String, @Query("limit") limit: Int): ApiEnvelope<List<TaskLogLine>>
    // ---- SDN (M6). Changes stay pending until applied with [applySdn].

    /** [kind] is `zones`, `vnets`, `controllers`, `ipams` or `dns`. */
    @GET("cluster/sdn/{kind}")
    suspend fun sdnList(@Path("kind") kind: String, @Query("pending") pending: Int? = 1): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("cluster/sdn/{kind}")
    suspend fun createSdn(@Path("kind") kind: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("cluster/sdn/{kind}/{id}")
    suspend fun updateSdn(@Path("kind") kind: String, @Path("id") id: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("cluster/sdn/{kind}/{id}")
    suspend fun deleteSdn(@Path("kind") kind: String, @Path("id") id: String): ApiEnvelope<JsonElement>

    @GET("cluster/sdn/vnets/{vnet}/subnets")
    suspend fun sdnSubnets(@Path("vnet") vnet: String, @Query("pending") pending: Int = 1): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("cluster/sdn/vnets/{vnet}/subnets")
    suspend fun createSdnSubnet(@Path("vnet") vnet: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("cluster/sdn/vnets/{vnet}/subnets/{subnet}")
    suspend fun updateSdnSubnet(@Path("vnet") vnet: String, @Path("subnet") subnet: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("cluster/sdn/vnets/{vnet}/subnets/{subnet}")
    suspend fun deleteSdnSubnet(@Path("vnet") vnet: String, @Path("subnet") subnet: String): ApiEnvelope<JsonElement>

    @GET("cluster/sdn/ipams/{ipam}/status")
    suspend fun ipamStatus(@Path("ipam") ipam: String): ApiEnvelope<JsonElement>

    /** Applies the pending SDN configuration on every node; answers the task's UPID. */
    @PUT("cluster/sdn")
    suspend fun applySdn(): ApiEnvelope<String>

    /** Throws the pending changes away (Proxmox VE 8.4 and later). */
    @POST("cluster/sdn/rollback")
    suspend fun rollbackSdn(): ApiEnvelope<JsonElement>
}
