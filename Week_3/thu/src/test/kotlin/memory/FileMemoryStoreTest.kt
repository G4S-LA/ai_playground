package memory

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileMemoryStoreTest {
    @Test
    fun `invariants are physically separate from dialogue and every memory layer`() {
        val root = Files.createTempDirectory("invariant-store-test")
        val store = FileMemoryStore(root)
        val session = store.createSession()

        store.remember(session.id, MemoryLayer.WORKING, "constraint", "Дедлайн — пятница")
        store.remember(session.id, MemoryLayer.LONG_TERM, "decision", "Ранее выбрали PostgreSQL")
        store.addInvariant("stack_constraint", "Запрещено использовать Python")

        val shortFile = root.resolve("short-term/${session.id}.json")
        val workingFile = root.resolve("working/${session.id}.json")
        val longFile = root.resolve("long-term/memories.json")
        val invariantsFile = root.resolve("invariants/invariants.json")
        assertTrue(invariantsFile.exists())
        assertTrue(invariantsFile.readText().contains("Запрещено использовать Python"))
        assertFalse(shortFile.readText().contains("Запрещено использовать Python"))
        assertFalse(workingFile.readText().contains("Запрещено использовать Python"))
        assertFalse(longFile.readText().contains("Запрещено использовать Python"))

        store.deleteSession(session.id)
        assertFalse(shortFile.exists())
        assertFalse(workingFile.exists())
        assertTrue(invariantsFile.exists())

        val next = store.createSession()
        assertTrue(store.snapshot(next.id).invariants.single().content.contains("Python"))
    }
}
