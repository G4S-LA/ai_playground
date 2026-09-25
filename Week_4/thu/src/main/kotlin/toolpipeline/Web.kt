package toolpipeline

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal fun runWeb(agent: PipelineAgent, config: AppConfig) {
    println("Pipeline UI: http://${config.webHost}:${config.webPort}")
    embeddedServer(Netty, host = config.webHost, port = config.webPort) {
        pipelineWebModule(agent)
    }.start(wait = true)
}

internal fun Application.pipelineWebModule(
    agent: PipelineAgent,
    runnerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val applicationLog = environment.log
    val registry = PipelineRunRegistry(agent, runnerScope)
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный запрос."))
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный JSON."))
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled pipeline request error", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Внутренняя ошибка сервера."))
        }
    }

    routing {
        get("/") {
            val html = checkNotNull(javaClass.classLoader.getResource("web/index.html")) {
                "web/index.html was not found"
            }.readText()
            call.respondText(html, ContentType.Text.Html)
        }
        staticResources("/static", "web/static")

        route("/api") {
            get("/tools") {
                call.respond(ToolsResponse(agent.availableTools()))
            }
            post("/runs") {
                val request = call.receive<StartPipelineRequest>()
                call.respond(HttpStatusCode.Accepted, StartPipelineResponse(registry.start(request.request)))
            }
            get("/runs/{id}") {
                val id = requireNotNull(call.parameters["id"]) { "Run id is missing." }
                val snapshot = registry.snapshot(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Запуск не найден."))
                call.respond(snapshot)
            }
        }
    }
}

internal data class StartPipelineRequest(val request: String = "")
internal data class StartPipelineResponse(val run: PipelineRunSnapshot)
internal data class ToolsResponse(val tools: List<AgentTool>)
internal data class ErrorResponse(val error: String)

internal data class PipelineRunSnapshot(
    val id: String,
    val request: String,
    val status: String,
    val currentAction: String,
    val answer: String?,
    val error: String?,
    val createdAt: String,
    val events: List<PipelineTraceEvent>,
)

private class PipelineRunRegistry(
    private val agent: PipelineAgent,
    private val scope: CoroutineScope,
) {
    private val runs = ConcurrentHashMap<String, MutablePipelineRun>()

    fun start(rawRequest: String): PipelineRunSnapshot {
        val request = rawRequest.trim()
        require(request.isNotEmpty()) { "Введите задачу для агента." }
        require(request.length <= 4_000) { "Задача не должна превышать 4000 символов." }
        val run = MutablePipelineRun(UUID.randomUUID().toString(), request)
        runs[run.id] = run
        scope.launch {
            try {
                val result = agent.run(request, run::record)
                run.complete(result.answer)
            } catch (error: Exception) {
                run.fail(error.message ?: "Неизвестная ошибка агента.")
            }
        }
        return run.snapshot()
    }

    fun snapshot(id: String): PipelineRunSnapshot? = runs[id]?.snapshot()
}

private class MutablePipelineRun(
    val id: String,
    private val request: String,
) {
    private val createdAt = Instant.now().toString()
    private val events = mutableListOf<PipelineTraceEvent>()
    private var status = "running"
    private var answer: String? = null
    private var error: String? = null

    @Synchronized
    fun record(event: PipelineTraceEvent) {
        events += event
    }

    @Synchronized
    fun complete(finalAnswer: String) {
        answer = finalAnswer
        status = "completed"
    }

    @Synchronized
    fun fail(message: String) {
        error = message
        status = "failed"
        events += PipelineTraceEvent(
            type = "pipeline_failed",
            message = "Пайплайн завершился с ошибкой",
            payload = message,
        )
    }

    @Synchronized
    fun snapshot(): PipelineRunSnapshot = PipelineRunSnapshot(
        id = id,
        request = request,
        status = status,
        currentAction = events.lastOrNull()?.message ?: "Запуск агента…",
        answer = answer,
        error = error,
        createdAt = createdAt,
        events = events.toList(),
    )
}
