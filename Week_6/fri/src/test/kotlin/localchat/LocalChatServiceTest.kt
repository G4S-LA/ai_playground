package localchat

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalChatServiceTest {
    @Test
    fun `sends saved dialog history to the model`() = runBlocking {
        val database = Files.createTempDirectory("private-chat-service").resolve("history.db")
        val firstService = LocalChatService(ChatRepository(database), FakeChatModel("Первый ответ"))
        val chat = firstService.createChat()
        firstService.send(chat.id, "Первый вопрос")

        val restartedModel = FakeChatModel("Второй ответ")
        val restartedService = LocalChatService(ChatRepository(database), restartedModel)
        val updated = restartedService.send(chat.id, "Второй вопрос")

        assertEquals(
            listOf("user", "assistant", "user"),
            restartedModel.requests.single().map { it.role },
        )
        assertEquals(4, updated.messages.size)
        assertEquals("Второй ответ", updated.messages.last().content)
    }
}
