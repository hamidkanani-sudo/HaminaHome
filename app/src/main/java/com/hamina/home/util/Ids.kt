package com.hamina.home.util

import java.security.MessageDigest

/** Stable identifiers derived from server + path, so they survive re-listing and cache expiry. */
object Ids {
    fun mediaId(serverId: String, path: String): String {
        val normalized = path.trim('/').replace('\\', '/')
        return MessageDigest.getInstance("SHA-256")
            .digest("$serverId|$normalized".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
