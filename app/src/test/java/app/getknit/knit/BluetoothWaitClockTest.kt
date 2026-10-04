package app.getknit.knit

import app.getknit.knit.legal.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every coroutine wait in `mesh/bluetooth/` says why it may stretch while the CPU sleeps (ADR 2026-10.pj9w).
 * `delay` and `withTimeout*` run on the monotonic clock, which stops in suspend: a wait a relink waits on that
 * uses one silently brings back the 60–90 s relinks, and only a suspending phone shows it. Such a wait goes
 * through `elapsedWait`; any other carries a `// stretches: <why that is fine>` comment on the line above (or
 * the comment block above), so a new one is a decision someone wrote down. Plain JVM: it reads the sources.
 */
class BluetoothWaitClockTest {
    @Test
    fun everyMonotonicWaitInTheBluetoothPlaneSaysWhyItMayStretch() {
        val dir = repoFile("app/src/main/java/app/getknit/knit/mesh/bluetooth")
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue("no Kotlin sources under $dir", files.isNotEmpty())
        var waits = 0
        val unmarked =
            files.flatMap { file ->
                val lines = file.readLines()
                lines.indices
                    .filter { i -> isWait(lines[i]) }
                    .onEach { waits++ }
                    .filterNot { i -> marked(lines, i) }
                    .map { i -> "${file.relativeTo(dir)}:${i + 1}: ${lines[i].trim()}" }
            }
        assertTrue("found no waits at all; the scan is broken", waits > 0)
        assertTrue(
            "these waits run on the monotonic clock, which stops while the CPU sleeps. Use `elapsedWait` if a relink " +
                "waits on it, else add `// stretches: <why>` above it (ADR 2026-10.pj9w):\n" + unmarked.joinToString("\n"),
            unmarked.isEmpty(),
        )
    }

    private fun isWait(line: String): Boolean {
        val code = line.trim()
        return !code.startsWith("//") && !code.startsWith("*") && !code.startsWith("import ") && WAIT.containsMatchIn(code)
    }

    /** The line itself, or the run of `//` lines right above it, carries the marker. */
    private fun marked(
        lines: List<String>,
        at: Int,
    ): Boolean {
        if (MARKER in lines[at]) return true
        var i = at - 1
        while (i >= 0 && lines[i].trim().startsWith("//")) {
            if (MARKER in lines[i]) return true
            i--
        }
        return false
    }

    private companion object {
        const val MARKER = "// stretches:"
        val WAIT = Regex("""\b(delay|withTimeoutOrNull|withTimeout)\s*\(|\bThread\.sleep\s*\(""")
    }
}
