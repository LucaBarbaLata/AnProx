package io.github.lucabarbalata.anprox.network.api

import io.github.lucabarbalata.anprox.model.pve.FormFields
import okhttp3.FormBody
import okhttp3.RequestBody

/**
 * Form bodies whose keys may repeat (`mailto=a&mailto=b`), for Proxmox array parameters.
 * Retrofit's `@FieldMap` can't express them.
 */
object Forms {
    fun body(fields: FormFields): RequestBody = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
}
