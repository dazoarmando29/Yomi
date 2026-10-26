package mihon.data.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.NetworkLegacyExtensionRepo
import mihon.data.extension.model.toAvailableExtensions
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class ExtensionStoreParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy repo metadata parses and keeps signing key and legacy flag`() {
        val repo = json.decodeFromString(NetworkLegacyExtensionRepo.serializer(), LEGACY_REPO_JSON)

        val store = repo.toExtensionStore("https://keiyoushi.github.io/extensions/repo.json")

        store.isLegacy shouldBe true
        store.name shouldBe "Keiyoushi"
        store.badgeLabel shouldBe "Keiyoushi"
        store.signingKey shouldBe "c9f6a502e1c1c9e3c0b8f6a4d2e7b9a1c3d5e7f9021426384a5c6e7f8091a2b3"
        store.contact.website shouldBe "https://keiyoushi.github.io/"
        store.extensionListUrl shouldBe null
        repo.indexV2 shouldBe null
    }

    @Test
    fun `legacy index entries map to available extensions with derived lib version`() {
        val entries = json.decodeFromString(
            ListSerializer(NetworkLegacyExtension.serializer()),
            LEGACY_INDEX_JSON,
        )

        val store = entries.single().toAvailableExtension(
            store = LEGACY_STORE,
            storeBaseUrl = "https://keiyoushi.github.io/extensions",
        )

        store.name shouldBe "Example Source"
        store.pkgName shouldBe "eu.kanade.tachiyomi.extension.en.example"
        store.apkUrl shouldBe "https://keiyoushi.github.io/extensions/apk/example.apk"
        store.iconUrl shouldBe
            "https://keiyoushi.github.io/extensions/icon/eu.kanade.tachiyomi.extension.en.example.png"
        store.libVersion shouldBe 1.4
        store.versionCode shouldBe 25L
        store.versionName shouldBe "1.4.25"
        store.lang shouldBe "en"
        store.contentWarning shouldBe ContentWarning.SAFE
        store.sources.single().name shouldBe "Example Source"
    }

    @Test
    fun `legacy entries without sources fall back to a single stub source`() {
        val entry = json.decodeFromString(NetworkLegacyExtension.serializer(), LEGACY_ENTRY_NO_SOURCES)

        val extension = entry.toAvailableExtension(LEGACY_STORE, "https://example.org/extensions")

        extension.sources.single().name shouldBe "Tachiyomi: No Sources"
        extension.sources.single().id shouldBe 0L
    }

    @Test
    fun `protobuf store index round-trips and maps extensions`() {
        val store = networkStore()

        val bytes = ProtoBuf.encodeToByteArray(NetworkExtensionStore.serializer(), store)
        val decoded = ProtoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), bytes)

        val extensionStore = decoded.toExtensionStore("https://example.org/index.min.proto")
        extensionStore.isLegacy shouldBe false
        extensionStore.signingKey shouldBe "feedfacefeedfacefeedfacefeedfacefeedfacefeedfacefeedfacefeedface"
        extensionStore.extensionListUrl shouldBe "https://example.org/extensions.pb"

        val extensions = decoded.extensionList!!.toAvailableExtensions(extensionStore)
        val extension = extensions.single()
        extension.pkgName shouldBe "eu.kanade.tachiyomi.extension.en.protoext"
        extension.libVersion shouldBe 1.6
        extension.contentWarning shouldBe ContentWarning.MIXED
        extension.sources.single().baseUrl shouldBe "https://proto.example"
        extension.store shouldBe extensionStore
    }

    private fun networkStore() = NetworkExtensionStore(
        name = "Example Store",
        badgeLabel = "Example",
        signingKey = "feedfacefeedfacefeedfacefeedfacefeedfacefeedfacefeedfacefeedface",
        contact = NetworkExtensionStore.Contact(website = "https://example.org", discord = null),
        extensionList = NetworkExtensionStore.ExtensionList(
            extensions = listOf(
                NetworkExtensionStore.Extension(
                    name = "Proto Ext",
                    packageName = "eu.kanade.tachiyomi.extension.en.protoext",
                    resources = NetworkExtensionStore.Resources(
                        apkUrl = "https://example.org/apk/protoext.apk",
                        iconUrl = "https://example.org/icon/protoext.png",
                    ),
                    extensionLib = "1.6",
                    versionCode = 3L,
                    versionName = "1.6.3",
                    contentWarning = NetworkExtensionStore.ContentWarning.MIXED,
                    sources = listOf(
                        NetworkExtensionStore.Source(
                            id = 42L,
                            name = "Proto Source",
                            language = "en",
                            homeUrl = "https://proto.example",
                        ),
                    ),
                ),
            ),
        ),
        extensionListUrl = "https://example.org/extensions.pb",
    )

    companion object {
        private val LEGACY_STORE = NetworkLegacyExtensionRepo(
            indexV2 = null,
            meta = NetworkLegacyExtensionRepo.Meta(
                name = "Keiyoushi",
                shortName = "Keiyoushi",
                website = "https://keiyoushi.github.io/",
                signingKeyFingerprint = "c9f6a502e1c1c9e3c0b8f6a4d2e7b9a1c3d5e7f9021426384a5c6e7f8091a2b3",
            ),
        ).toExtensionStore("https://keiyoushi.github.io/extensions/repo.json")

        private const val LEGACY_REPO_JSON = """
            {
                "index_v2": null,
                "meta": {
                    "name": "Keiyoushi",
                    "shortName": "Keiyoushi",
                    "website": "https://keiyoushi.github.io/",
                    "signingKeyFingerprint": "c9f6a502e1c1c9e3c0b8f6a4d2e7b9a1c3d5e7f9021426384a5c6e7f8091a2b3"
                }
            }
        """

        private const val LEGACY_INDEX_JSON = """
            [
                {
                    "name": "Tachiyomi: Example Source",
                    "pkg": "eu.kanade.tachiyomi.extension.en.example",
                    "apk": "example.apk",
                    "lang": "en",
                    "code": 25,
                    "version": "1.4.25",
                    "nsfw": 0,
                    "sources": [
                        {"id": 123456789, "lang": "en", "name": "Example Source", "baseUrl": "https://example.com"}
                    ]
                }
            ]
        """

        private const val LEGACY_ENTRY_NO_SOURCES = """
            {
                "name": "Tachiyomi: No Sources",
                "pkg": "eu.kanade.tachiyomi.extension.en.nosources",
                "apk": "nosources.apk",
                "lang": "en",
                "code": 1,
                "version": "1.4.1",
                "nsfw": 1,
                "sources": null
            }
        """
    }
}
