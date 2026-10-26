package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.system.logcat

/**
 * One-shot cleanup of extension store entries pointing at index URLs that are
 * known to be dead. Earlier builds told users to add Keiyoushi via
 * `keiyoushi.github.io/extensions`; every index path under it now returns
 * HTTP 404 and the bare landing page is not a parsable index, so those entries
 * only produce refresh errors.
 *
 * Only exact matches of [KNOWN_STALE_INDEX_URLS] are removed. User-added
 * repositories are never touched, and nothing is installed or trusted.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class RemoveStaleExtensionRepositoriesMigration(
    private val repository: ExtensionStoreRepository,
) : Migration {
    override val version: Float = 36f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        val stores = try {
            repository.getAll()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error listing extension repositories for stale cleanup" }
            return@withContext true
        }
        for (store in stores) {
            if (normalize(store.indexUrl) !in KNOWN_STALE_INDEX_URLS) continue
            try {
                repository.remove(store.indexUrl)
                logcat { "Removed stale extension repository: ${store.indexUrl}" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error removing stale extension repository: ${store.indexUrl}" }
            }
        }
        return@withContext true
    }

    companion object {
        /**
         * Verified dead on 2026-10-26: `keiyoushi.github.io/extensions/{repo.json,
         * index.json, index.min.json, index.pb}` all return HTTP 404 and the bare
         * landing page serves HTML, not an index. The working Keiyoushi index is
         * `https://github.com/keiyoushi/extensions/raw/repo/index.pb` (seeded by
         * [SeedExtensionRepositoriesMigration]).
         */
        internal val KNOWN_STALE_INDEX_URLS = setOf(
            "https://keiyoushi.github.io/extensions",
            "https://keiyoushi.github.io/extensions/repo.json",
            "https://keiyoushi.github.io/extensions/index.json",
            "https://keiyoushi.github.io/extensions/index.min.json",
            "https://keiyoushi.github.io/extensions/index.pb",
        )

        internal fun normalize(indexUrl: String): String = indexUrl.trim().removeSuffix("/")
    }
}
