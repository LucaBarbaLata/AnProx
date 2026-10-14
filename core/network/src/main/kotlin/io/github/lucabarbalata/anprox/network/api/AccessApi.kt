package io.github.lucabarbalata.anprox.network.api

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.auth.Realm
import io.github.lucabarbalata.anprox.model.auth.TicketResponse
import io.github.lucabarbalata.anprox.model.pve.VersionInfo
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Endpoints that PVE and PBS share. Paths are relative to `/api2/json/`.
 * Retrofit generates the implementation; see docs/architecture.md → "Networking".
 */
interface AccessApi {
    /** Unauthenticated: the realms offered on the login screen. */
    @GET("access/domains")
    suspend fun realms(): ApiEnvelope<List<Realm>>

    /** Log in, answer a TFA challenge, or renew a ticket (password = current ticket). */
    @FormUrlEncoded
    @POST("access/ticket")
    suspend fun ticket(@FieldMap fields: Map<String, String>): ApiEnvelope<TicketResponse>

    @GET("version")
    suspend fun version(): ApiEnvelope<VersionInfo>
}
