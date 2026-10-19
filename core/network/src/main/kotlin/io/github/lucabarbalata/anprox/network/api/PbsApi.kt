package io.github.lucabarbalata.anprox.network.api

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.pbs.DatastoreInfo
import io.github.lucabarbalata.anprox.model.pbs.DatastoreUsage
import io.github.lucabarbalata.anprox.model.pve.AptRepositories
import io.github.lucabarbalata.anprox.model.pve.AptUpdate
import io.github.lucabarbalata.anprox.model.pve.NodeService
import io.github.lucabarbalata.anprox.model.pve.NodeSubscription
import io.github.lucabarbalata.anprox.model.pve.PackageVersion
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

/** Proxmox Backup Server endpoints (see docs/api-coverage.md). PBS is one node, addressed as `localhost`. */
interface PbsApi {
    @GET("status/datastore-usage")
    suspend fun datastoreUsage(): ApiEnvelope<List<DatastoreUsage>>

    @GET("admin/datastore")
    suspend fun datastores(): ApiEnvelope<List<DatastoreInfo>>

    @GET("nodes/localhost/tasks")
    suspend fun tasks(@Query("limit") limit: Int): ApiEnvelope<List<TaskSummary>>

    @GET("nodes/localhost/tasks/{upid}/status")
    suspend fun taskStatus(@Path("upid") upid: String): ApiEnvelope<TaskStatus>

    @DELETE("nodes/localhost/tasks/{upid}")
    suspend fun stopTask(@Path("upid") upid: String): ApiEnvelope<JsonElement>

    @GET("nodes/localhost/tasks/{upid}/log")
    suspend fun taskLog(@Path("upid") upid: String, @Query("start") start: Int, @Query("limit") limit: Int): ApiEnvelope<List<TaskLogLine>>

    // ---- datastore content (M8). [params] name a namespace (`ns`), group or snapshot.

    @GET("admin/datastore/{store}/status")
    suspend fun datastoreStatus(@Path("store") store: String): ApiEnvelope<JsonObject>

    @GET("admin/datastore/{store}/namespace")
    suspend fun namespaces(@Path("store") store: String): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("admin/datastore/{store}/namespace")
    suspend fun createNamespace(@Path("store") store: String, @Field("name") name: String, @Field("parent") parent: String?): ApiEnvelope<JsonElement>

    @DELETE("admin/datastore/{store}/namespace")
    suspend fun deleteNamespace(@Path("store") store: String, @Query("ns") ns: String, @Query("delete-groups") deleteGroups: Int): ApiEnvelope<JsonElement>

    @GET("admin/datastore/{store}/groups")
    suspend fun groups(@Path("store") store: String, @QueryMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("admin/datastore/{store}/groups")
    suspend fun deleteGroup(@Path("store") store: String, @QueryMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @GET("admin/datastore/{store}/snapshots")
    suspend fun snapshots(@Path("store") store: String, @QueryMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("admin/datastore/{store}/snapshots")
    suspend fun deleteSnapshot(@Path("store") store: String, @QueryMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("admin/datastore/{store}/notes")
    suspend fun setNotes(@Path("store") store: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("admin/datastore/{store}/protected")
    suspend fun setProtected(@Path("store") store: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** Verifies a snapshot, a group or (without them) the whole datastore or namespace; a task. */
    @FormUrlEncoded
    @POST("admin/datastore/{store}/verify")
    suspend fun verify(@Path("store") store: String, @FieldMap params: Map<String, String>): ApiEnvelope<String>

    /** Prunes one group; with `dry-run=1` only says what it would remove. */
    @FormUrlEncoded
    @POST("admin/datastore/{store}/prune")
    suspend fun prune(@Path("store") store: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @GET("admin/datastore/{store}/gc")
    suspend fun gcStatus(@Path("store") store: String): ApiEnvelope<JsonObject>

    @POST("admin/datastore/{store}/gc")
    suspend fun startGc(@Path("store") store: String): ApiEnvelope<String>

    /** Entries of a snapshot's catalog under `filepath` (base64, or `root`). */
    @GET("admin/datastore/{store}/catalog")
    suspend fun catalog(@Path("store") store: String, @QueryMap params: Map<String, String>): ApiEnvelope<JsonElement>

    /** A file from a snapshot, or a directory as a zip. Not wrapped in `{"data": …}`. */
    @Streaming
    @GET("admin/datastore/{store}/pxar-file-download")
    suspend fun downloadFile(@Path("store") store: String, @QueryMap params: Map<String, String>): ResponseBody

    @GET("config/datastore/{store}")
    suspend fun datastoreConfig(@Path("store") store: String): ApiEnvelope<JsonObject>

    /** `maintenance-mode=type=read-only…`, or `delete=maintenance-mode`. */
    @FormUrlEncoded
    @PUT("config/datastore/{store}")
    suspend fun updateDatastore(@Path("store") store: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    // ---- jobs and remotes. [kind] is `sync`, `verify` or `prune`.

    @GET("admin/{kind}")
    suspend fun jobs(@Path("kind") kind: String): ApiEnvelope<JsonElement>

    @POST("admin/{kind}/{id}/run")
    suspend fun runJob(@Path("kind") kind: String, @Path("id") id: String): ApiEnvelope<String>

    @FormUrlEncoded
    @PUT("config/prune/{id}")
    suspend fun updatePruneJob(@Path("id") id: String, @FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @GET("config/remote")
    suspend fun remotes(): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("config/remote")
    suspend fun createRemote(@FieldMap params: Map<String, String>): ApiEnvelope<JsonElement>

    @DELETE("config/remote/{name}")
    suspend fun deleteRemote(@Path("name") name: String): ApiEnvelope<JsonElement>

    // ---- the node: the same paths and answers as Proxmox VE's, with [node] `localhost`.

    @GET("nodes/{node}/status")
    suspend fun nodeStatus(@Path("node") node: String): ApiEnvelope<JsonObject>

    @GET("nodes/{node}/apt/update")
    suspend fun aptUpdates(@Path("node") node: String): ApiEnvelope<List<AptUpdate>>

    @FormUrlEncoded
    @POST("nodes/{node}/apt/update")
    suspend fun refreshAptIndex(@Path("node") node: String, @Field("notify") notify: Int = 0): ApiEnvelope<String>

    @GET("nodes/{node}/apt/changelog")
    suspend fun aptChangelog(@Path("node") node: String, @Query("name") name: String, @Query("version") version: String?): ApiEnvelope<String>

    @GET("nodes/{node}/apt/versions")
    suspend fun packageVersions(@Path("node") node: String): ApiEnvelope<List<PackageVersion>>

    @GET("nodes/{node}/apt/repositories")
    suspend fun aptRepositories(@Path("node") node: String): ApiEnvelope<AptRepositories>

    @FormUrlEncoded
    @POST("nodes/{node}/apt/repositories")
    suspend fun setAptRepositoryEnabled(
        @Path("node") node: String,
        @Field("path") path: String,
        @Field("index") index: Int,
        @Field("enabled") enabled: Int,
        @Field("digest") digest: String?,
    ): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @PUT("nodes/{node}/apt/repositories")
    suspend fun addStandardAptRepository(@Path("node") node: String, @Field("handle") handle: String, @Field("digest") digest: String?): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/subscription")
    suspend fun subscription(@Path("node") node: String): ApiEnvelope<NodeSubscription>

    @FormUrlEncoded
    @PUT("nodes/{node}/subscription")
    suspend fun setSubscriptionKey(@Path("node") node: String, @Field("key") key: String): ApiEnvelope<JsonElement>

    @DELETE("nodes/{node}/subscription")
    suspend fun removeSubscriptionKey(@Path("node") node: String): ApiEnvelope<JsonElement>

    @GET("nodes/{node}/services")
    suspend fun services(@Path("node") node: String): ApiEnvelope<List<NodeService>>

    @POST("nodes/{node}/services/{service}/{action}")
    suspend fun serviceAction(@Path("node") node: String, @Path("service") service: String, @Path("action") action: String): ApiEnvelope<String>

    @GET("nodes/{node}/journal")
    suspend fun journal(@Path("node") node: String, @Query("lastentries") lastEntries: Int): ApiEnvelope<List<String>>
}
