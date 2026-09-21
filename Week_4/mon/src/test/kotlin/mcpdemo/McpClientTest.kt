package mcpdemo

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpClientTest {
    @Test
    fun `client connects and receives tools from public MCP server`(): Unit = runBlocking {
        val tools = listAvailableTools().associateBy { it.name }

        assertTrue(tools.isNotEmpty())
        val questionTool = assertNotNull(tools["ask_question"])
        assertTrue("repoName" in questionTool.inputSchema.required.orEmpty())
    }
}
