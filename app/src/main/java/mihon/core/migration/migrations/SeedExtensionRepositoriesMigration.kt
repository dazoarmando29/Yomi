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
 * Seeds the commonly used community extension repositories so users can install
 * extensions without hunting for index URLs. These are third-party providers;
 * entries are visible and removable in Extension repositories, and no extension
 * is installed or trusted automatically.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SeedExtensionRepositoriesMigration(
    private val repository: ExtensionStoreRepository,
) : Migration {
    override val version: Float = 35f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        for ((indexUrl, name) in SEEDED_REPOSITORIES) {
            try {
                repository.insertFromPreference(indexUrl = indexUrl, name = name)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error seeding extension repository: $indexUrl" }
            }
        }
        return@withContext true
    }

    internal companion object {
        internal val SEEDED_REPOSITORIES = listOf(
            "https://github.com/keiyoushi/extensions/raw/repo/index.pb" to "Keiyoushi",
            "https://github.com/yuzono/manga-repo/raw/repo/repo.json" to "Yuzono",
            "https://github.com/manti-X/extensions/raw/repo/index.pb" to "Manti-X",
        )
    }
}
