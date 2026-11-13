package com.tthayer.lighttrimet

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A stop the user has saved for live tracking. The name is TriMet's stop description. */
@Serializable
data class SavedStop(
    val id: Long,
    val name: String,
) {
    val stateLabel: String get() = "#$id"
}

class SavedStopsRepository(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    val stops: Flow<List<SavedStop>> = dataStore.data.map { prefs -> decode(prefs[SAVED_STOPS]) }

    suspend fun add(stop: SavedStop) {
        dataStore.edit { prefs ->
            val current = decode(prefs[SAVED_STOPS])
            if (current.none { it.id == stop.id }) {
                prefs[SAVED_STOPS] = json.encodeToString(current + stop)
            } else {
                prefs[SAVED_STOPS] = json.encodeToString(current.map { if (it.id == stop.id) stop else it })
            }
        }
    }

    suspend fun remove(id: Long) {
        dataStore.edit { prefs ->
            prefs[SAVED_STOPS] = json.encodeToString(decode(prefs[SAVED_STOPS]).filterNot { it.id == id })
        }
    }

    private fun decode(raw: String?): List<SavedStop> =
        raw?.let { runCatching { json.decodeFromString<List<SavedStop>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        val SAVED_STOPS = stringPreferencesKey("saved_stops")
    }
}
