package memory

import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileMemoryStoreTest {
    @Test
    fun `three layers are physically separate and have different lifetime`() {
        val root = Files.createTempDirectory("memory-store-test")
        val store = FileMemoryStore(root)
        val first = store.createSession()

        store.remember(first.id, MemoryLayer.SHORT_TERM, "dialogue_note", "Только этот диалог")
        store.remember(first.id, MemoryLayer.WORKING, "goal", "Завершить текущую задачу")
        store.remember(first.id, MemoryLayer.LONG_TERM, "profile", "Пользователь любит краткие ответы")

        val shortFile = root.resolve("short-term/${first.id}.json")
        val workingFile = root.resolve("working/${first.id}.json")
        val longFile = root.resolve("long-term/memories.json")
        val defaultProfileFile = root.resolve("profiles/default.json")
        assertTrue(shortFile.exists())
        assertTrue(workingFile.exists())
        assertTrue(longFile.exists())
        assertTrue(defaultProfileFile.exists())
        assertTrue(shortFile.readText().contains("Только этот диалог"))
        assertFalse(shortFile.readText().contains("Завершить текущую задачу"))
        assertTrue(workingFile.readText().contains("Завершить текущую задачу"))
        assertFalse(workingFile.readText().contains("Пользователь любит"))
        assertTrue(longFile.readText().contains("Пользователь любит"))

        val second = store.createSession()
        val secondSnapshot = store.snapshot(second.id)
        assertTrue(secondSnapshot.session.messages.isEmpty())
        assertTrue(secondSnapshot.working.isEmpty())
        assertEquals(listOf("Пользователь любит краткие ответы"), secondSnapshot.longTerm.map { it.content })

        store.deleteSession(first.id)
        assertFalse(shortFile.exists())
        assertFalse(workingFile.exists())
        assertTrue(longFile.exists())
        assertTrue(defaultProfileFile.exists())
    }

    @Test
    fun `profile persists across sessions and deleted profile falls back to default`() {
        val root = Files.createTempDirectory("profile-store-test")
        val store = FileMemoryStore(root)
        val profile = store.createProfile(
            name = "Разработчик",
            style = "Технический",
            format = "Markdown",
            constraints = listOf("Приводить примеры"),
        )
        val first = store.createSession(profile.id)
        val second = store.createSession(profile.id)

        assertEquals(profile.id, store.snapshot(first.id).profile.id)
        assertEquals(profile.id, store.snapshot(second.id).profile.id)
        assertTrue(root.resolve("profiles/${profile.id}.json").exists())

        store.deleteSession(first.id)
        assertTrue(root.resolve("profiles/${profile.id}.json").exists())
        store.deleteProfile(profile.id)
        assertEquals(UserProfiles.DEFAULT_ID, store.snapshot(second.id).profile.id)
    }

    @Test
    fun `legacy session without profile id is migrated to default profile`() {
        val root = Files.createTempDirectory("legacy-profile-test")
        val store = FileMemoryStore(root)
        val sessionId = UUID.randomUUID().toString()
        val sessionFile = root.resolve("short-term/$sessionId.json")
        sessionFile.writeText(
            """
            {
              "id": "$sessionId",
              "title": "Старый диалог",
              "createdAt": "2026-01-01T00:00:00Z",
              "updatedAt": "2026-01-01T00:00:00Z",
              "messages": []
            }
            """.trimIndent()
        )

        val snapshot = store.snapshot(sessionId)

        assertEquals(UserProfiles.DEFAULT_ID, snapshot.profile.id)
        assertEquals(UserProfiles.DEFAULT_ID, snapshot.session.profileId)
        assertTrue(sessionFile.readText().contains("\"profileId\": \"default\""))
    }

    @Test
    fun `forget removes only explicitly selected memory item`() {
        val store = FileMemoryStore(Files.createTempDirectory("memory-forget-test"))
        val session = store.createSession()
        val keep = store.remember(session.id, MemoryLayer.WORKING, "note", "Оставить")
        val remove = store.remember(session.id, MemoryLayer.WORKING, "constraint", "Удалить")

        store.forget(session.id, MemoryLayer.WORKING, remove.id)

        assertEquals(listOf(keep), store.snapshot(session.id).working)
    }
}
