package com.hamina.home.data.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Stores last played position and timestamp for each media item.
 * Keyed by a stable media id (serverId + path).
 */
class PlaybackRepository(private val dataStore: DataStore<Preferences>) {

    private val KEY_PLAYBACK = stringPreferencesKey("playback_positions")

    data class PlaybackInfo(
        val positionMs: Long,
        val durationMs: Long,
        val lastPlayedAt: Long
    )

    /** An entry for the "Continue watching" row. */
    data class RecentPlay(
        val id: String,
        val serverId: String,
        val path: String,
        val name: String,
        val positionMs: Long,
        val durationMs: Long,
        val lastPlayedAt: Long
    )

    suspend fun getPosition(mediaId: String): PlaybackInfo? {
        val raw = dataStore.data.first()[KEY_PLAYBACK] ?: return null
        return try {
            val root = JSONObject(raw)
            if (!root.has(mediaId)) return null
            val o = root.getJSONObject(mediaId)
            PlaybackInfo(
                positionMs = o.optLong("pos", 0L),
                durationMs = o.optLong("dur", 0L),
                lastPlayedAt = o.optLong("at", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** All saved positions keyed by media id (used to show resume info in folder listings). */
    suspend fun getAll(): Map<String, PlaybackInfo> {
        val raw = dataStore.data.first()[KEY_PLAYBACK] ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            val out = HashMap<String, PlaybackInfo>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.optJSONObject(k) ?: continue
                out[k] = PlaybackInfo(
                    positionMs = o.optLong("pos", 0L),
                    durationMs = o.optLong("dur", 0L),
                    lastPlayedAt = o.optLong("at", 0L)
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Most recently played, unfinished videos that have enough info to be started again. */
    suspend fun getRecent(limit: Int = 12): List<RecentPlay> {
        val raw = dataStore.data.first()[KEY_PLAYBACK] ?: return emptyList()
        return try {
            val root = JSONObject(raw)
            val out = ArrayList<RecentPlay>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.optJSONObject(k) ?: continue
                val sid = o.optString("sid", "")
                val path = o.optString("path", "")
                val name = o.optString("name", "")
                val pos = o.optLong("pos", 0L)
                val dur = o.optLong("dur", 0L)
                if (sid.isEmpty() || path.isEmpty() || name.isEmpty()) continue
                if (pos < 5_000 || (dur > 0 && dur - pos < 10_000)) continue
                out.add(RecentPlay(k, sid, path, name, pos, dur, o.optLong("at", 0L)))
            }
            out.sortedByDescending { it.lastPlayedAt }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun savePosition(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        serverId: String = "",
        path: String = "",
        name: String = ""
    ) {
        dataStore.edit { prefs ->
            val root = try {
                JSONObject(prefs[KEY_PLAYBACK] ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }
            root.put(mediaId, JSONObject().apply {
                put("pos", positionMs)
                put("dur", durationMs)
                put("at", System.currentTimeMillis())
                if (serverId.isNotEmpty()) put("sid", serverId)
                if (path.isNotEmpty()) put("path", path)
                if (name.isNotEmpty()) put("name", name)
            })
            // Keep only last ~200 entries to avoid unbounded growth
            if (root.length() > 220) {
                val keys = root.keys().asSequence().toList()
                val sorted = keys.map { k ->
                    k to root.getJSONObject(k).optLong("at", 0L)
                }.sortedBy { it.second }
                sorted.take(sorted.size - 200).forEach { root.remove(it.first) }
            }
            prefs[KEY_PLAYBACK] = root.toString()
        }
    }

    suspend fun clearPosition(mediaId: String) {
        dataStore.edit { prefs ->
            val root = try {
                JSONObject(prefs[KEY_PLAYBACK] ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }
            root.remove(mediaId)
            prefs[KEY_PLAYBACK] = root.toString()
        }
    }
}
