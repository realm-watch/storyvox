package `in`.jphe.storyvox.plugin.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Plugin-seam Phase 1 (#384) — KSP SymbolProcessor that emits one
 * Hilt `@Module @Provides @IntoSet` factory per `@SourcePlugin`-
 * annotated class.
 *
 * For each annotated `FictionSource` implementation, the processor
 * generates a Kotlin file in
 * `in.jphe.storyvox.plugin.generated.<module-mangled-id>` that
 * contributes a `SourcePluginDescriptor` into the multibinding set
 * the `SourcePluginRegistry` consumes. The generated file is
 * `installed in SingletonComponent`, matching the descriptor's
 * singleton scope.
 *
 * ## Why per-annotation file generation
 *
 * Each consumer module's KSP pass only sees the annotated symbols
 * declared in that module. So each module generates its own factories
 * — there is no central "list all plugins" pass. Hilt's multibinding
 * machinery then merges every module's `@IntoSet` contributions at
 * the app's `@HiltAndroidApp` graph-build step.
 *
 * That's also why the generated module name is mangled per source
 * class: `KvmrSource` → `KvmrSource_SourcePluginModule`. Two modules
 * declaring the same name would collide at compile time even when
 * they're in different packages, because Hilt aggregates by FQN at
 * the bytecode level.
 *
 * ## Generated shape (illustrative)
 *
 * ```kotlin
 * package in.jphe.storyvox.plugin.generated
 *
 * @dagger.Module
 * @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
 * internal object KvmrSource_SourcePluginModule {
 *     @dagger.Provides
 *     @dagger.multibindings.IntoSet
 *     @javax.inject.Singleton
 *     fun provideKvmrSourceDescriptor(
 *         source: `in`.jphe.storyvox.source.kvmr.KvmrSource,
 *     ): in.jphe.storyvox.data.source.plugin.SourcePluginDescriptor =
 *         in.jphe.storyvox.data.source.plugin.SourcePluginDescriptor(
 *             id = "kvmr",
 *             displayName = "KVMR",
 *             defaultEnabled = true,
 *             category = in.jphe.storyvox.data.source.plugin.SourceCategory.AudioStream,
 *             supportsFollow = false,
 *             supportsSearch = true,
 *             source = source,
 *         )
 * }
 * ```
 *
 * The fully-qualified names in the generated file are deliberate —
 * staying away from `import` resolution avoids brittle behaviour when
 * the consumer module's source set has shadowed names.
 */
class SourcePluginProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotated = resolver
            .getSymbolsWithAnnotation(SOURCE_PLUGIN_FQN)
            .toList()

        val (ready, deferred) = annotated.partition { it.validate() }

        for (symbol in ready) {
            if (symbol !is KSClassDeclaration) {
                logger.error(
                    "@SourcePlugin can only be applied to a class — found $symbol",
                    symbol,
                )
                continue
            }
            val annotation = symbol.annotations.firstOrNull {
                it.shortName.asString() == "SourcePlugin" &&
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == SOURCE_PLUGIN_FQN
            } ?: continue

            try {
                emitModule(symbol, annotation)
            } catch (t: Throwable) {
                logger.error("Failed to emit plugin module for ${symbol.qualifiedName?.asString()}: ${t.message}", symbol)
            }
        }

        return deferred
    }

    private fun emitModule(target: KSClassDeclaration, annotation: KSAnnotation) {
        val targetFqn = target.qualifiedName?.asString()?.let(::escapeKotlinFqn)
            ?: error("@SourcePlugin target ${target.simpleName.asString()} has no qualified name")
        val targetSimple = target.simpleName.asString()

        val args: Map<String, Any?> = annotation.arguments.associate { it.name?.asString().orEmpty() to it.value }
        val id = args["id"] as? String
            ?: error("@SourcePlugin on $targetFqn missing required id")
        val displayName = args["displayName"] as? String
            ?: error("@SourcePlugin on $targetFqn missing required displayName")
        val defaultEnabled = (args["defaultEnabled"] as? Boolean) ?: false
        val supportsFollow = (args["supportsFollow"] as? Boolean) ?: false
        val supportsSearch = (args["supportsSearch"] as? Boolean) ?: false
        // `category` comes through as a KSType pointing at the enum entry — or,
        // when defaulted, as a default-value sentinel resolvable via the same
        // mechanism. Either way, we want the qualified name of the enum entry.
        val categoryFqn: String = when (val raw = args["category"]) {
            is KSType -> raw.declaration.qualifiedName?.asString()?.let(::escapeKotlinFqn)
                ?: DEFAULT_CATEGORY_FQN
            null -> DEFAULT_CATEGORY_FQN
            else -> {
                // Some KSP versions surface enum entries as their toString().
                // Best-effort: assume "SourceCategory.Text"-style fallback.
                DEFAULT_CATEGORY_FQN
            }
        }

        val moduleSimpleName = "${targetSimple}_SourcePluginModule"
        val generatedFqn = "$GENERATED_PACKAGE_RAW.$moduleSimpleName"

        val containingFile = target.containingFile
        val dependencies = if (containingFile != null) {
            Dependencies(aggregating = false, containingFile)
        } else {
            Dependencies.ALL_FILES
        }

        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = GENERATED_PACKAGE_RAW,
            fileName = moduleSimpleName,
            extensionName = "kt",
        ).use { out ->
            OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                writer.write(
                    buildFileContent(
                        moduleSimpleName = moduleSimpleName,
                        targetFqn = targetFqn,
                        id = id,
                        displayName = displayName,
                        defaultEnabled = defaultEnabled,
                        categoryFqn = categoryFqn,
                        supportsFollow = supportsFollow,
                        supportsSearch = supportsSearch,
                    ),
                )
            }
        }

        logger.info(
            "Generated $generatedFqn for @SourcePlugin($id) on $targetFqn",
            target,
        )
    }

    private fun buildFileContent(
        moduleSimpleName: String,
        targetFqn: String,
        id: String,
        displayName: String,
        defaultEnabled: Boolean,
        categoryFqn: String,
        supportsFollow: Boolean,
        supportsSearch: Boolean,
    ): String {
        val escapedId = id.replace("\"", "\\\"")
        val escapedDisplayName = displayName.replace("\"", "\\\"")
        return buildString {
            appendLine("// Generated by :core-plugin-ksp from @SourcePlugin($escapedId) — DO NOT EDIT.")
            appendLine("@file:Suppress(\"RedundantVisibilityModifier\", \"unused\")")
            appendLine()
            appendLine("package $GENERATED_PACKAGE_KOTLIN")
            appendLine()
            appendLine("@dagger.Module")
            appendLine("@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)")
            appendLine("internal object $moduleSimpleName {")
            appendLine("    @dagger.Provides")
            appendLine("    @dagger.multibindings.IntoSet")
            appendLine("    @javax.inject.Singleton")
            appendLine("    fun provideDescriptor(")
            appendLine("        source: $targetFqn,")
            appendLine("    ): $DESCRIPTOR_FQN =")
            appendLine("        $DESCRIPTOR_FQN(")
            appendLine("            id = \"$escapedId\",")
            appendLine("            displayName = \"$escapedDisplayName\",")
            appendLine("            defaultEnabled = $defaultEnabled,")
            appendLine("            category = $categoryFqn,")
            appendLine("            supportsFollow = $supportsFollow,")
            appendLine("            supportsSearch = $supportsSearch,")
            appendLine("            source = source,")
            appendLine("        )")
            appendLine("}")
        }
    }

    /**
     * Wrap any Kotlin hard-keyword segment of a dotted FQN in
     * backticks so the generated source parses. The storyvox package
     * root `in.jphe.storyvox...` trips this because `in` is a Kotlin
     * hard keyword; without escaping, the parser sees `in.foo` as a
     * malformed `in` expression. Only segments that match a hard
     * keyword get backticks — leaving the rest alone keeps generated
     * code readable.
     */
    private fun escapeKotlinFqn(fqn: String): String =
        fqn.split('.').joinToString(".") { segment ->
            if (segment in KOTLIN_HARD_KEYWORDS) "`$segment`" else segment
        }

    private companion object {
        private val KOTLIN_HARD_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for",
            "fun", "if", "in", "interface", "is", "null", "object", "package",
            "return", "super", "this", "throw", "true", "try", "typealias",
            "typeof", "val", "var", "when", "while",
        )
        // Used with KSP's Resolver.getSymbolsWithAnnotation — must be the
        // unescaped FQN. Kotlin's `in` package segment is a soft keyword,
        // not a hard one, so this lookup form works without backticks.
        const val SOURCE_PLUGIN_FQN = "in.jphe.storyvox.data.source.plugin.SourcePlugin"
        // KSP's CodeGenerator.createNewFile takes the package name in
        // its unescaped (filesystem-mirror) form; backticks only appear
        // in generated SOURCE code that references the package.
        const val GENERATED_PACKAGE_RAW = "in.jphe.storyvox.plugin.generated"
        // Forms used in the generated source code itself — `in` is escaped
        // because it's a Kotlin keyword in expression position.
        const val GENERATED_PACKAGE_KOTLIN = "`in`.jphe.storyvox.plugin.generated"
        const val DESCRIPTOR_FQN = "`in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor"
        const val DEFAULT_CATEGORY_FQN = "`in`.jphe.storyvox.data.source.plugin.SourceCategory.Text"
    }
}

/**
 * SymbolProcessorProvider entry point — registered via
 * `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
 * so the Kotlin compiler discovers it automatically when this jar is
 * on the consumer module's KSP classpath.
 */
class SourcePluginProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        SourcePluginProcessor(environment.codeGenerator, environment.logger)
}
