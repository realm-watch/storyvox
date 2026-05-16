package `in`.jphe.storyvox.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #618 — pins the cold-launch perf guard contract.
 *
 * Three structural assertions that survive cross-cutting refactors:
 *  1. The thresholds file exists at the path the CI script reads.
 *  2. The constants the script greps for are present + match the
 *     ship-budget JP committed to in the v1.0 readiness audit (tablet
 *     1500 ms, phone 500 ms).
 *  3. The script itself exists and is executable.
 *
 * Tested at the :app unit-test layer (rather than inside :baselineprofile,
 * which is a com.android.test module and runs as instrumented tests
 * only) so the guard's *contract* is verified on every PR's `:app:testDebugUnitTest`
 * pass — even though the actual benchmark run only happens on tag-push
 * with a connected device.
 *
 * The test reads files relative to the project root. The test runner's
 * working directory is the :app module, so we resolve `../` to reach
 * the root. If the module layout ever changes (rare), the test fails
 * with a clear "couldn't find $path" message rather than silently
 * passing on a broken integration.
 */
class ColdLaunchPerfGuardTest {

    /** :app's user.dir at test time is `<root>/app`; the guard files
     *  live above that. Computed once so a misconfigured runner
     *  surfaces in a single assertion rather than four. */
    private val projectRoot: File = File(System.getProperty("user.dir") ?: ".").parentFile
        ?: error("Could not resolve project root from user.dir")

    @Test
    fun `thresholds file exists`() {
        val file = File(
            projectRoot,
            "baselineprofile/src/main/kotlin/in/jphe/storyvox/baselineprofile/ColdLaunchThresholds.kt",
        )
        assertTrue("expected ${file.absolutePath} to exist", file.exists())
    }

    @Test
    fun `tablet budget is 1500 ms`() {
        val file = File(
            projectRoot,
            "baselineprofile/src/main/kotlin/in/jphe/storyvox/baselineprofile/ColdLaunchThresholds.kt",
        )
        val body = file.readText()
        // Same regex shape the CI script uses, so a parse-breaking
        // refactor (e.g. changing `Long = 1_500L` to a kotlinx.duration
        // expression) is caught here AND in the script's grep.
        val match = Regex("""const val TABLET_BUDGET_MS: Long = (\d[\d_]*)L""").find(body)
        assertTrue("TABLET_BUDGET_MS constant not found in $file", match != null)
        val value = match!!.groupValues[1].replace("_", "").toLong()
        assertEquals(1500L, value)
    }

    @Test
    fun `phone budget is 500 ms`() {
        val file = File(
            projectRoot,
            "baselineprofile/src/main/kotlin/in/jphe/storyvox/baselineprofile/ColdLaunchThresholds.kt",
        )
        val body = file.readText()
        val match = Regex("""const val PHONE_BUDGET_MS: Long = (\d[\d_]*)L""").find(body)
        assertTrue("PHONE_BUDGET_MS constant not found in $file", match != null)
        val value = match!!.groupValues[1].replace("_", "").toLong()
        assertEquals(500L, value)
    }

    @Test
    fun `check-cold-launch script exists`() {
        val script = File(projectRoot, "scripts/check-cold-launch.sh")
        assertTrue(
            "expected ${script.absolutePath} to exist — this script is the CI guard for Issue #618",
            script.exists(),
        )
        // We can't assert executability across platforms reliably
        // (Windows runners report `canExecute() == false` on .sh files
        // even when the file mode is correct), but file presence + the
        // shebang line is enough to verify the script was committed.
        val firstLine = script.useLines { it.firstOrNull().orEmpty() }
        assertTrue(
            "script must start with a bash shebang",
            firstLine.startsWith("#!") && firstLine.contains("bash"),
        )
    }
}
