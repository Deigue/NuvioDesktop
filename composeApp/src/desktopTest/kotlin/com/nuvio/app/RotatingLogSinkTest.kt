package com.nuvio.app

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotatingLogSinkTest {

    private val stamp = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")

    private fun newDir(): File = Files.createTempDirectory("nuvio-log-sink").toFile()

    private fun RotatingLogSink.writeText(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        write(bytes, 0, bytes.size)
    }

    private fun sink(dir: File, maxBytes: Long = 1L shl 20) =
        RotatingLogSink(dir, "test.log", maxBytes, backupCount = 3, zone = ZoneId.of("UTC"))

    @Test
    fun `every line gets exactly one local timestamp even when written in pieces`() {
        val dir = newDir()
        val sink = sink(dir)
        // PrintStream(autoflush) style: text and newline arrive as separate writes.
        sink.writeText("Info: (A) first")
        sink.writeText("\n")
        sink.writeText("Info: (B) second\nInfo: (C) third\n")
        sink.flush()

        val lines = File(dir, "test.log").readLines()
        assertEquals(3, lines.size, lines.toString())
        lines.forEach { line -> assertTrue(stamp.containsMatchIn(line), "unstamped: $line") }
        assertEquals(listOf("Info: (A) first", "Info: (B) second", "Info: (C) third"), lines.map { it.replace(stamp, "") })
        // No stamp was inserted mid-line where the chunk boundary fell.
        assertEquals(1, stamp.findAll(lines[0] + " ").count())
    }

    @Test
    fun `rotation moves the active file aside and keeps writing`() {
        val dir = newDir()
        val sink = sink(dir, maxBytes = 200)
        repeat(12) { sink.writeText("line $it padded to be a bit longer than it needs\n") }
        sink.flush()

        assertTrue(File(dir, "test.log.1").exists(), dir.list()?.toList().toString())
        val active = File(dir, "test.log").readText()
        assertTrue(active.isNotEmpty())
        assertTrue(active.trimEnd().lines().last().endsWith("line 11 padded to be a bit longer than it needs"))
    }

    @Test
    fun `a rotation that cannot rename still keeps the log alive`() {
        val dir = newDir()
        val sink = sink(dir, maxBytes = 200)
        // Stand-in for another process pinning a file the shuffle has to move: the first step,
        // .2 -> .3, cannot replace a non-empty directory, so the whole shuffle fails the way the
        // Windows sharing violation did.
        File(dir, "test.log.2").writeText("old backup")
        val blocker = File(dir, "test.log.3").apply { mkdirs(); File(this, "occupied").writeText("x") }
        repeat(12) { sink.writeText("line $it padded to be a bit longer than it needs\n") }
        sink.flush()

        val active = File(dir, "test.log").readText()
        assertTrue(active.contains("line 11 padded"), "later writes were lost:\n$active")
        assertTrue(active.contains("[log] rotation"), "no marker explaining the degraded rotation:\n$active")
        assertFalse(active.contains("line 0 padded"), "the file was neither rotated nor truncated:\n$active")
        blocker.deleteRecursively()
    }
}
