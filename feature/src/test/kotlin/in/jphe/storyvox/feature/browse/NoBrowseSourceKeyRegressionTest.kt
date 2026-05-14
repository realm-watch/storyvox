package `in`.jphe.storyvox.feature.browse

import org.junit.Test
import java.io.File

/**
 * Plugin-seam Phase 3 (#384) — exhaustiveness regression test. Phase
 * 3 deleted the legacy `BrowseSourceKey` enum; any new code that
 * accidentally re-introduces a reference is a structural step
 * backward. This test greps the production source tree and fails the
 * build if the literal `BrowseSourceKey` shows up outside the
 * allow-listed places (kdoc references to the deleted enum, which
 * intentionally mention the name for historical context).
 *
 * Pattern: Find all `.kt` files under `feature/src/main` and any
 * other module's `src/main` directory accessible from this test, and
 * check the lines that contain `BrowseSourceKey`. Each hit must
 * either be inside a kdoc comment block or this test fails.
 */
class NoBrowseSourceKeyRegressionTest {

    @Test fun `BrowseSourceKey appears only in kdoc references after Phase 3`() {
        val root = findProjectRoot()
        val violations = mutableListOf<String>()

        scanProductionSources(root).forEach { file ->
            file.readLines().forEachIndexed { idx, line ->
                if (!line.contains("BrowseSourceKey")) return@forEachIndexed
                // Allow kdoc / line-comment references — Phase 3 kept
                // several kdoc paragraphs that mention the deleted
                // enum for historical context (so future readers
                // understand what was deleted). Any non-comment
                // reference is a regression.
                val trimmed = line.trimStart()
                val isComment = trimmed.startsWith("*") ||
                    trimmed.startsWith("//") ||
                    trimmed.startsWith("/*")
                if (!isComment) {
                    violations += "${file.relativeTo(root)}:${idx + 1}: $line"
                }
            }
        }

        if (violations.isNotEmpty()) {
            val detail = violations.joinToString("\n  ", prefix = "  ")
            error(
                """
                Phase 3 (#384) regression — BrowseSourceKey leaked back into the production
                source tree. The enum was deleted in v0.5.31; iterate
                `SourcePluginRegistry.descriptors` instead. Violations:
                $detail
                """.trimIndent(),
            )
        }
    }

    /** Walks up from the test class's working directory looking for
     *  the project root (the dir containing `settings.gradle.kts`). */
    private fun findProjectRoot(): File {
        var dir = File("").absoluteFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not find project root from ${File("").absoluteFile}")
    }

    /** Returns every `*.kt` file under `<module>/src/main/` for every
     *  module that's a sibling of this test's module. The grep is
     *  scoped to production sources so it doesn't trip on its own
     *  test fixture references. */
    private fun scanProductionSources(root: File): List<File> {
        val out = mutableListOf<File>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { module ->
            val main = File(module, "src/main")
            if (main.isDirectory) {
                main.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { out += it }
            }
        }
        return out
    }
}
