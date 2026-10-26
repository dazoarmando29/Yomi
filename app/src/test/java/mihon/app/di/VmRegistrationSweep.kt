package mihon.app.di

/**
 * Source-level sweep that mirrors what [MihonViewModelFactory] resolves at runtime.
 *
 * Every ViewModel obtained through `metroViewModel<T>()` or
 * `assistedMetroViewModel<T, F>()` must contribute itself (or its assisted
 * factory) into Metro's ViewModel map via `@ContributesIntoMap` + the matching
 * key annotation. A missing registration compiles fine but crashes at runtime
 * with "Unknown model class ... MihonViewModelFactory.create" (this happened in
 * v0.4.0), so this sweep makes the mistake fail unit tests instead.
 */
internal object VmRegistrationSweep {

    enum class Kind { PLAIN, ASSISTED }

    data class Usage(
        val type: String,
        val factoryType: String?,
        val kind: Kind,
    )

    private val usageRegex = Regex(
        """\b(assistedMetroViewModel|metroViewModel)\s*<\s*([A-Za-z0-9_.]+)\s*(?:,\s*([A-Za-z0-9_.]+))?""",
    )

    fun findUsages(text: String): List<Usage> {
        return usageRegex.findAll(text).map { match ->
            val kind = if (match.groupValues[1].startsWith("assisted")) Kind.ASSISTED else Kind.PLAIN
            Usage(
                type = match.groupValues[2],
                factoryType = match.groupValues[3].takeIf { it.isNotBlank() },
                kind = kind,
            )
        }.toList()
    }

    fun findDeclarations(text: String, simpleName: String): List<IntRange> {
        val regex = Regex("""(?<![\w.])(?:class|interface|object|typealias)\s+${Regex.escape(simpleName)}\b""")
        return regex.findAll(text).map { it.range }.toList()
    }

    /**
     * A declaration is registered when its annotation header (the contiguous
     * annotation lines right above the declaration) carries the right key
     * annotation plus `@ContributesIntoMap`, or - for assisted/manual factories -
     * its class body carries them on a nested factory declaration.
     */
    fun isRegistered(fileText: String, declRange: IntRange, usage: Usage): Boolean {
        val header = annotationHeaderBefore(fileText, declRange.first)
        val body = classBody(fileText, declRange.last + 1)

        val direct = header.contains("@ViewModelKey") && header.contains("@ContributesIntoMap")
        val factoryInBody = body.contains("@ManualViewModelAssistedFactoryKey") && body.contains("@ContributesIntoMap")

        return when (usage.kind) {
            Kind.PLAIN -> direct || factoryInBody
            Kind.ASSISTED -> {
                val factorySimple = usage.factoryType?.substringAfterLast('.') ?: "Factory"
                val factoryDecls = findDeclarations(fileText, factorySimple)
                val factoryRegistered = factoryDecls.isNotEmpty() && factoryDecls.all { factoryDecl ->
                    val factoryHeader = annotationHeaderBefore(fileText, factoryDecl.first)
                    factoryHeader.contains("@ManualViewModelAssistedFactoryKey") &&
                        factoryHeader.contains("@ContributesIntoMap")
                }
                val explicitKey = header.contains("@ManualViewModelAssistedFactoryKey") &&
                    header.contains("@ContributesIntoMap")
                factoryRegistered || explicitKey || factoryInBody
            }
        }
    }

    /**
     * Collects the annotation lines directly above [declStart], stopping at the
     * first line of real code (the declaration's own line is ignored entirely,
     * so modifiers like `data class X` do not hide the annotations above).
     */
    internal fun annotationHeaderBefore(fileText: String, declStart: Int): String {
        val before = fileText.substring(0, declStart)
        val lastNewline = before.lastIndexOf('\n')
        val header = if (lastNewline >= 0) before.substring(0, lastNewline) else ""
        val collected = mutableListOf<String>()
        for (line in header.lines().asReversed()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("//")) continue
            if (trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("*/")) continue
            if (trimmed.startsWith("@")) {
                collected.add(line)
                continue
            }
            break
        }
        return collected.asReversed().joinToString("\n")
    }

    /**
     * Text of the class body starting at the first brace that is not inside the
     * constructor parameter list, or empty when the declaration has no body.
     */
    internal fun classBody(fileText: String, from: Int): String {
        var parenDepth = 0
        var bodyStart = -1
        var i = from
        while (i < fileText.length) {
            when (fileText[i]) {
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> if (parenDepth == 0) {
                    bodyStart = i
                }
            }
            if (bodyStart >= 0) break
            i++
        }
        if (bodyStart < 0) return ""
        var depth = 0
        var j = bodyStart
        while (j < fileText.length) {
            when (fileText[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return fileText.substring(bodyStart, j + 1)
                }
            }
            j++
        }
        return fileText.substring(bodyStart)
    }
}
