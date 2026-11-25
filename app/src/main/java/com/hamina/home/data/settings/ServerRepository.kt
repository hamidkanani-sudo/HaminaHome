package com.hamina.home.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    private fun serializeServers(list: List<SmbServer>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("host", s.host)
                put("share", s.share)
                put("username", s.username)
                put("password", s.password)
                put("domain", s.domain)
                put("port", s.port)
                put("isFavorite", s.isFavorite)
            })
        }
        return arr.toString()
    }

    private fun parseServers(raw: String): List<SmbServer> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SmbServer(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    host = o.optString("host"),
                    share = o.optString("share"),
                    username = o.optString("username"),
                    password = o.optString("password"),
                    domain = o.optString("domain"),
                    port = o.optInt("port", 445),
                    isFavorite = o.optBoolean("isFavorite", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
