package io.github.lucabarbalata.anprox.network.api

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.pbs.DatastoreInfo
import io.github.lucabarbalata.anprox.model.pbs.DatastoreUsage
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import retrofit2.http.GET
import retrofit2.http.Query

/** Proxmox Backup Server endpoints used so far (see docs/api-coverage.md). */
interface PbsApi {
    @GET("status/datastore-usage")
    suspend fun datastoreUsage(): ApiEnvelope<List<DatastoreUsage>>

    @GET("admin/datastore")
    suspend fun datastores(): ApiEnvelope<List<DatastoreInfo>>

    /** PBS is always a single node, addressed as `localhost`. */
    @GET("nodes/localhost/tasks")
    suspend fun tasks(@Query("limit") limit: Int): ApiEnvelope<List<TaskSummary>>
}
