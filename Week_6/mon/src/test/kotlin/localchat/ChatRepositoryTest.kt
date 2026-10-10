package localchat

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRepositoryTest {
    @Test
    fun `persists lists and deletes chats with their messages`() {
        val database = Files.createTempDirectory("local-chat-repository").resolve("history.db")
        val firstInstance = ChatRepository(database)
        val created = firstInstance.createChat()

        val updated = firstInstance.appendTurn(created.id, "Запомни кодовое слово маяк", "Запомнил: маяк")
        assertEquals("Запомни кодовое слово маяк", updated.title)
        assertEquals(listOf("user", "assistant"), updated.messages.map { it.role })

        val restarted = ChatRepository(database)
        val restored = restarted.getChat(created.id)
        assertEquals(2, restored?.messages?.size)
        assertEquals("Запомнил: маяк", restored?.messages?.last()?.content)
        assertEquals(2, restarted.listChats().single().messageCount)

        restarted.deleteChat(created.id)
        assertNull(restarted.getChat(created.id))
        assertTrue(restarted.listChats().isEmpty())
    }

    @Test
    fun `shortens title from the first user message`() {
        val database = Files.createTempDirectory("local-chat-title").resolve("history.db")
        val repository = ChatRepository(database)
        val chat = repository.createChat()

        val updated = repository.appendTurn(chat.id, "Очень длинный запрос ".repeat(8), "Ответ")

        assertEquals(ChatRepository.MAX_TITLE_LENGTH, updated.title.length)
        assertTrue(updated.title.endsWith("…"))
    }
}
