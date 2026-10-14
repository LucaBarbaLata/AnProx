package io.github.lucabarbalata.anprox.network.auth

import io.github.lucabarbalata.anprox.model.auth.AuthSession
import io.github.lucabarbalata.anprox.model.auth.TfaChallenge

/** Outcome of the first login step. */
sealed interface LoginResult {
    data class Success(val session: AuthSession) : LoginResult

    /**
     * Password accepted, second factor required. Pass [partialTicket] back to
     * [io.github.lucabarbalata.anprox.network.ProxmoxConnection.completeTfa].
     */
    data class NeedsTfa(val partialTicket: String, val challenge: TfaChallenge) : LoginResult
}
