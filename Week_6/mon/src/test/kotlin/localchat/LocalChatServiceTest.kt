package localchat

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalChatServiceTest {
    @Test
    fun `restores sqlite history and sends it to the local model`() = runBlocking {
        val database = Files.createTempDirectory("local-chat-service").resolve("history.db")
        val firstModel = FakeChatModel("Запомнил")
        val firstService = LocalChatService(ChatRepository(database), firstModel, "Системная инструкция")
        val chat = firstService.createChat()
        firstService.send(chat.id, "Мой цвет — зелёный")

        val secondModel = FakeChatModel("Зелёный")
        val restarted = LocalChatService(ChatRepository(database), secondModel, "Системная инструкция")
        val result = restarted.send(chat.id, "Какой мой цвет?")

        assertEquals("Зелёный", result.message.content)
        assertEquals(
            listOf("system", "user", "assistant", "user"),
            secondModel.requests.single().map { it.role },
        )
        assertEquals("Мой цвет — зелёный", secondModel.requests.single()[1].content)
        assertEquals(4, result.chat.messages.size)
    }
}
