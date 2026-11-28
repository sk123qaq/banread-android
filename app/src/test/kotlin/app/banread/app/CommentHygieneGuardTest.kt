package app.banread.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentHygieneGuardTest {

    @Test
    fun `first-party Kotlin sources contain no decorative banners or commented-out code`() {
        val root = findRepositoryRoot()
        val violations = MODULES
            .asSequence()
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { module -> module.walkTopDown().onEnter { it.name != "build" } }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (DISALLOWED.any { it.containsMatchIn(line) }) {
                        "${file.relativeTo(root).path}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue("Comment hygiene violations:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun findRepositoryRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && MODULES.all { File(dir, it).isDirectory }) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Repository root not found from ${File("").absolutePath}")
    }

    private companion object {
        val MODULES = listOf(
            "app",
            "core-model",
            "module-ai",
            "module-companion",
            "module-library",
            "module-reader",
            "module-sync",
            "module-viz",
        )

        val DISALLOWED = listOf(
            Regex("""^\s*//\s*[=\-_*#~]{4,}.*$"""),
            Regex("""^\s*//\s*@\w+"""),
            Regex(
                """^\s*//\s*(?:(?:private|internal|public|protected|override|open|abstract|sealed|data|value|inline|suspend|operator|infix|tailrec|external|actual|expect|lateinit|const)\s+)*(?:fun|val|var|class|object|interface|typealias|import|package|constructor|init)\b""",
            ),
            Regex("""^\s*//\s*(?:if|for|while|when)\s*\("""),
            Regex("""^\s*//\s*(?:return|throw)\b"""),
            Regex("""^\s*//\s*}\s*$"""),
        )
    }
}
