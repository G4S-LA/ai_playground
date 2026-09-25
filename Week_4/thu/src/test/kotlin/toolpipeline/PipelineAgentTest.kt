package toolpipeline

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineAgentTest {
    @Test
    fun `agent automatically runs all tools and forwards complete results`() = runBlocking {
        val output = createTempDirectory("tool-pipeline-agent-")
        try {
            val config = AppConfig.fromEnvironment(
                demo = true,
                environment = mapOf("PIPELINE_OUTPUT_DIR" to output.toString()),
            )
            val trace = mutableListOf<PipelineTraceEvent>()
            val run = PipelineAgent(
                model = DemoPipelineLanguageModel(
                    query = "MCP tool schemas and pipeline tests",
                    fileName = "result.md",
                ),
                gateway = PipelineMcpGateway(config),
            ).run("Run the complete pipeline", trace::add)

            assertEquals(listOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TOOL), run.executions.map { it.name })
            assertEquals(
                listOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TOOL),
                trace.filter { it.type == "tool_started" }.mapNotNull { it.toolName },
            )
            assertEquals(4, trace.count { it.type == "model_started" })
            assertEquals("pipeline_completed", trace.last().type)

            val gson = Gson()
            val searchJson = gson.fromJson(run.executions[0].result, JsonObject::class.java)
            val summarizeInput = gson.toJsonTree(run.executions[1].arguments["searchResult"])
            assertEquals(searchJson, summarizeInput, "summarize must receive the complete search output")

            val summaryJson = gson.fromJson(run.executions[1].result, JsonObject::class.java)
            val saveInput = gson.toJsonTree(run.executions[2].arguments["summary"])
            assertEquals(summaryJson, saveInput, "saveToFile must receive the complete summarize output")

            val saved = output.resolve("result.md")
            assertTrue(saved.exists())
            assertContains(saved.readText(), "MCP tool schemas and pipeline tests")
            assertContains(run.answer, saved.toString())
        } finally {
            output.toFile().deleteRecursively()
        }
    }
}
