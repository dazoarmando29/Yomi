package eu.kanade.tachiyomi.smartsource.migration

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SnapshotHistoryEntry(
    val mangaId: Long,
    val snapshot: MangaSnapshot,
)

@Serializable
data class SnapshotHistoryFile(val entries: List<SnapshotHistoryEntry> = emptyList())

/**
 * Recoverable snapshots of manga state taken before a smart migration.
 *
 * Snapshots are stored locally as JSON and never leave the device. Restoring a snapshot
 * re-applies the recorded library membership, notes, chapters, and history so a failed
 * or unwanted migration can be rolled back. Original records are never deleted during
 * migration, so snapshots are a safety net rather than the only protection.
 */
class MigrationSnapshotStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun save(snapshot: MangaSnapshot) {
        val entries = load().filterNot { it.mangaId == snapshot.mangaId } +
            SnapshotHistoryEntry(snapshot.mangaId, snapshot)
        saveRaw(json.encodeToString(SnapshotHistoryFile.serializer(), SnapshotHistoryFile(entries)))
    }

    @Synchronized
    fun latest(mangaId: Long): MangaSnapshot? {
        return load().lastOrNull { it.mangaId == mangaId }?.snapshot
    }

    @Synchronized
    fun all(): List<MangaSnapshot> = load().map { it.snapshot }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOTS).apply()
    }

    private fun load(): List<SnapshotHistoryEntry> {
        val raw = prefs.getString(KEY_SNAPSHOTS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(SnapshotHistoryFile.serializer(), raw).entries
        }.getOrDefault(emptyList())
    }

    private fun saveRaw(raw: String) {
        prefs.edit().putString(KEY_SNAPSHOTS, raw).apply()
    }

    companion object {
        private const val PREFS_NAME = "smart_migration_snapshots"
        private const val KEY_SNAPSHOTS = "snapshots"

        fun encode(snapshots: List<MangaSnapshot>): String {
            return Json.encodeToString(
                SnapshotHistoryFile.serializer(),
                SnapshotHistoryFile(snapshots.map { SnapshotHistoryEntry(it.mangaId, it) }),
            )
        }

        fun decode(raw: String): List<MangaSnapshot> {
            return Json.decodeFromString(SnapshotHistoryFile.serializer(), raw).entries.map { it.snapshot }
        }
    }
}
