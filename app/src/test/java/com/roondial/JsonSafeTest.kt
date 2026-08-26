package com.roondial

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards a bug the other tests are structurally unable to see.
 *
 * `optString` returns "null" for a JSON null on Android and "" on the JVM.
 * These tests run on the JVM against the reference org.json, so a null
 * image_key looks empty here and looks like the string "null" on a phone —
 * where it is then requested as artwork. No behavioural test written here can
 * fail on that, so this one reads the source instead.
 */
class JsonSafeTest {

    private val sourceRoot = File("src/main/java/com/roondial")

    @Test
    fun noProductionSourceCallsOptString() {
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "JsonSafe.kt" }
            .mapNotNull { file ->
                val lines = file.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        line.contains("optString") && !line.trimStart().startsWith("*")
                    }
                if (lines.isEmpty()) null else {
                    "${file.name}: " + lines.joinToString(", ") { (i, _) -> "line ${i + 1}" }
                }
            }
            .toList()

        assertTrue(
            "use str() from JsonSafe.kt instead of optString — on Android it " +
                "returns \"null\" for a JSON null:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun theSourceRootIsWhereThisThinksItIs() {
        // A wrong path would make the scan above pass by finding nothing.
        assertTrue("cannot find the sources to scan", sourceRoot.isDirectory)
        assertTrue(
            "expected some Kotlin sources",
            sourceRoot.walkTopDown().any { it.extension == "kt" }
        )
    }
}
