package mihon.app.di

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import mihon.app.di.VmRegistrationSweep.Kind
import mihon.app.di.VmRegistrationSweep.Usage
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against the v0.4.0 startup crash: a ViewModel used via
 * `metroViewModel` / `assistedMetroViewModel` but never registered in Metro's
 * ViewModel map compiles fine and then crashes at runtime with
 * "Unknown model class ... MihonViewModelFactory.create". This sweep fails the
 * unit test suite instead.
 */
class ViewModelRegistrationSweepTest {

    @Test
    fun `every view model used via metro view model helpers is registered in the DI map`() {
        val root = projectRoot()
        val sources = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
            .toList()

        sources.size shouldBeGreaterThan 100

        val usages = sources.flatMap { file ->
            VmRegistrationSweep.findUsages(file.readText()).map { it to file }
        }
        usages.size shouldBeGreaterThan 40

        val failures = mutableListOf<String>()
        for ((usage, usageFile) in usages) {
            val simpleName = usage.type.substringAfterLast('.')
            val declFiles = sources.filter { file ->
                VmRegistrationSweep.findDeclarations(file.readText(), simpleName).isNotEmpty()
            }
            val candidateFiles = declFiles.filter { it == usageFile }.ifEmpty { declFiles }
            if (candidateFiles.isEmpty()) {
                failures += "${usage.kind} ${usage.type}: declaration not found (used in ${usageFile.name})"
                continue
            }
            for (declFile in candidateFiles) {
                val text = declFile.readText()
                val decls = VmRegistrationSweep.findDeclarations(text, simpleName)
                for (decl in decls) {
                    if (!VmRegistrationSweep.isRegistered(text, decl, usage)) {
                        failures += "${usage.kind} ${usage.type}: '${declFile.name}' declares '$simpleName' " +
                            "without @ContributesIntoMap registration"
                    }
                }
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError("Unregistered ViewModels:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `sweep accepts an assisted view model with a registered nested factory`() {
        val source = """
            class MangaViewModel @AssistedInject constructor() : ViewModel() {
                @ManualViewModelAssistedFactoryKey
                @ContributesIntoMap(AppScope::class)
                @AssistedFactory
                interface Factory : ViewModelAssistedFactory<MangaViewModel> {
                    fun create(mangaId: Long): MangaViewModel
                }
            }
        """.trimIndent()

        registered(source, Usage("MangaViewModel", "MangaViewModel.Factory", Kind.ASSISTED)) shouldBe true
    }

    @Test
    fun `sweep accepts a plain view model with ViewModelKey`() {
        val source = """
            @Inject
            @ViewModelKey
            @ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
            class HomeDashboardViewModel(private val x: Int) : ViewModel()
        """.trimIndent()

        registered(source, Usage("HomeDashboardViewModel", null, Kind.PLAIN)) shouldBe true
    }

    @Test
    fun `sweep rejects a view model without any registration`() {
        val source = """
            class UnregisteredViewModel(private val x: Int) : ViewModel() {
                fun refresh() = Unit
            }
        """.trimIndent()

        registered(source, Usage("UnregisteredViewModel", null, Kind.PLAIN)) shouldBe false
    }

    @Test
    fun `sweep rejects a key annotation without ContributesIntoMap`() {
        val source = """
            @Inject
            @ViewModelKey
            class SneakyViewModel(private val x: Int) : ViewModel()
        """.trimIndent()

        registered(source, Usage("SneakyViewModel", null, Kind.PLAIN)) shouldBe false
    }

    @Test
    fun `sweep rejects an assisted view model whose factory is not registered`() {
        val source = """
            class BrokenViewModel @AssistedInject constructor() : ViewModel() {
                @AssistedFactory
                interface Factory : ViewModelAssistedFactory<BrokenViewModel> {
                    fun create(id: Long): BrokenViewModel
                }
            }
        """.trimIndent()

        registered(source, Usage("BrokenViewModel", "BrokenViewModel.Factory", Kind.ASSISTED)) shouldBe false
    }

    @Test
    fun `sweep ignores unregistered classes that are never obtained via metro helpers`() {
        val usages = VmRegistrationSweep.findUsages(
            """
                val model = plainViewModel<PlainViewModel>()
                val other = OtherFactory.create<NopeViewModel>()
            """.trimIndent(),
        )

        usages.shouldBeEmpty()
    }

    private fun registered(source: String, usage: Usage): Boolean {
        val decls = VmRegistrationSweep.findDeclarations(source, usage.type.substringAfterLast('.'))
        decls.shouldNotBeEmpty()
        return decls.any { VmRegistrationSweep.isRegistered(source, it, usage) }
    }

    private fun projectRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = start.absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("Project root not found from $start")
    }
}
