package com.hamina.home.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hamina.home.data.model.SmbServer
import com.hamina.home.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists the list of SMB servers using DataStore.
 */
class ServerRepository(private val dataStore: DataStore<Preferences>) {

    private val KEY_SERVERS = stringPreferencesKey("smb_servers")
    private val KEY_ENCRYPTED_V1 = booleanPreferencesKey("smb_servers_encrypted_v1")

    val servers: Flow<List<SmbServer>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_SERVERS] ?: "[]"
        parseServers(raw)
    }

    suspend fun getServersOnce(): List<SmbServer> = servers.first()

    suspend fun addServer(server: SmbServer) {
        dataStore.edit { prefs ->
            val current = parseServers(prefs[KEY_SERVERS] ?: "[]").toMutableList()
            val toAdd = if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server
            current.add(toAdd)
            prefs[KEY_SERVERS] = serializeServers(current)
        }
    }

    suspend fun updateServer(server: SmbServer) {
        dataStore.edit { prefs ->
            val current = parseServers(prefs[KEY_SERVERS] ?: "[]").toMutableList()
            val idx = current.indexOfFirst { it.id == server.id }
            if (idx >= 0) {
                current[idx] = server
                prefs[KEY_SERVERS] = serializeServers(current)
            }
        }
    }

    suspend fun deleteServer(serverId: String) {
        dataStore.edit { prefs ->
            val current = parseServers(prefs[KEY_SERVERS] ?: "[]").filter { it.id != serverId }
            prefs[KEY_SERVERS] = serializeServers(current)
        }
    }

    /** One-time migration: re-saves the server list so legacy plain-text passwords become encrypted. */
    suspend fun migratePasswordsIfNeeded() {
        dataStore.edit { prefs ->
            if (prefs[KEY_ENCRYPTED_V1] == true) return@edit
            val raw = prefs[KEY_SERVERS]
            if (raw != null) {
                val parsed = try {
                    parseServersStrict(raw)
                } catch (_: Exception) {
                    return@edit // unreadable list: leave it untouched
                }
                prefs[KEY_SERVERS] = serializeServers(parsed)
            }
            prefs[KEY_ENCRYPTED_V1] = true
        }
    }

    private fun serializeServers(list: List<SmbServer>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("host", s.host)
                put("share", s.share)
                put("username", s.username)
                put("password", SecretBox.encrypt(s.password))
                put("domain", s.domain)
                put("port", s.port)
                put("isFavorite", s.isFavorite)
            })
        }
        return arr.toString()
    }

    private fun parseServers(raw: String): List<SmbServer> {
        return try {
            parseServersStrict(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseServersStrict(raw: String): List<SmbServer> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SmbServer(
                id = o.optString("id"),
                name = o.optString("name"),
                host = o.optString("host"),
                share = o.optString("share"),
                username = o.optString("username"),
                password = SecretBox.decrypt(o.optString("password")),
                domain = o.optString("domain"),
                port = o.optInt("port", 445),
                isFavorite = o.optBoolean("isFavorite", false)
            )
        }
    }
}
