package localchat

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRepositoryTest {
    @Test
    fun `persists and deletes chats with their messages`() {
        val database = Files.createTempDirectory("private-chat-repository").resolve("history.db")
        val firstInstance = ChatRepository(database)
        val created = firstInstance.createChat()
        firstInstance.appendTurn(created.id, "Запомни слово маяк", "Запомнил")

        val restarted = ChatRepository(database)
        val restored = restarted.getChat(created.id)
        assertEquals("Запомни слово маяк", restored?.title)
        assertEquals(listOf("user", "assistant"), restored?.messages?.map { it.role })
        assertEquals(2, restarted.listChats().single().messageCount)

        restarted.deleteChat(created.id)
        assertNull(restarted.getChat(created.id))
        assertTrue(restarted.listChats().isEmpty())
    }
}
