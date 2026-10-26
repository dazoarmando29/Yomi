package mihon.core.migration.migrations

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.core.migration.MigrationContext
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.jupiter.api.Test

class RemoveStaleExtensionRepositoriesMigrationTest {

    private val repository = mockk<ExtensionStoreRepository>(relaxed = true)
    private val migration = RemoveStaleExtensionRepositoriesMigration(repository)

    @Test
    fun `removes every known-stale index url including trailing-slash variant`() = runBlocking {
        val storedUrls = RemoveStaleExtensionRepositoriesMigration.KNOWN_STALE_INDEX_URLS +
            "https://keiyoushi.github.io/extensions/"
        coEvery { repository.getAll() } returns storedUrls.map { store(it) }

        migration(MigrationContext(dryrun = false, previousVersion = 0)) shouldBe true

        for (url in storedUrls) {
            coVerify(exactly = 1) { repository.remove(url) }
        }
    }

    @Test
    fun `keeps seeded and user-added repositories`() = runBlocking {
        val keptUrls = SeedExtensionRepositoriesMigration.SEEDED_REPOSITORIES.map { it.first } +
            "https://example.org/index.min.json"
        coEvery { repository.getAll() } returns keptUrls.map { store(it) }

        migration(MigrationContext(dryrun = false, previousVersion = 0)) shouldBe true

        coVerify(exactly = 0) { repository.remove(any()) }
    }

    @Test
    fun `stale list never overlaps seeded repositories`() {
        val stale = RemoveStaleExtensionRepositoriesMigration.KNOWN_STALE_INDEX_URLS
        for ((indexUrl, _) in SeedExtensionRepositoriesMigration.SEEDED_REPOSITORIES) {
            (RemoveStaleExtensionRepositoriesMigration.normalize(indexUrl) in stale) shouldBe false
        }
    }

    @Test
    fun `cleanup runs after seeding`() {
        (migration.version > SeedExtensionRepositoriesMigration(repository).version) shouldBe true
    }

    private fun store(indexUrl: String) = ExtensionStore(
        indexUrl = indexUrl,
        name = "Test",
        badgeLabel = "Test",
        signingKey = "",
        contact = ExtensionStore.Contact(website = "", discord = null),
        isLegacy = true,
        extensionListUrl = null,
    )
}
