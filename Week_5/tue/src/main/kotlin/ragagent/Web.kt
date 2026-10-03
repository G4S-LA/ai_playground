package ragagent

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

internal fun runWeb(
    agent: RagAgentService,
    questions: ControlQuestionRepository,
    config: RagConfig,
) {
    println("RAG comparison UI: http://${config.host}:${config.port}")
    println("LLM: ${agent.modelDescription}")
    println("Embeddings: ${agent.embeddingDescription}")
    embeddedServer(Netty, host = config.host, port = config.port) {
        ragWebModule(agent, questions)
    }.start(wait = true)
}

internal fun Application.ragWebModule(
    agent: RagAgentService,
    repository: ControlQuestionRepository,
    registry: EvaluationRegistry = EvaluationRegistry(agent, repository.questions),
) {
    val applicationLog = environment.log
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный запрос."))
        }
        exception<IllegalStateException> { call, error ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(error.message ?: "Сервис недоступен."))
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный JSON."))
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled RAG request error", error)
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
            get("/info") {
                call.respond(
                    InfoResponse(
                        chatModel = agent.modelDescription,
                        embeddingModel = agent.embeddingDescription,
                        stats = agent.stats(),
                        controlQuestions = repository.questions.size,
                    ),
                )
            }
            get("/questions") {
                call.respond(QuestionsResponse(repository.questions))
            }
            post("/index") {
                call.respond(agent.rebuildStructuredIndex())
            }
            post("/answer") {
                val request = call.receive<AnswerRequest>()
                call.respond(agent.answer(request.question, parseMode(request.mode), request.topK))
            }
            post("/compare") {
                val request = call.receive<CompareRequest>()
                call.respond(agent.compare(request.question, request.topK))
            }
            post("/evaluations") {
                val request = call.receive<StartEvaluationRequest>()
                call.respond(HttpStatusCode.Accepted, registry.start(request.topK))
            }
            get("/evaluations/{id}") {
                val id = requireNotNull(call.parameters["id"]) { "Evaluation id is missing." }
                val snapshot = registry.snapshot(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Запуск оценки не найден."))
                call.respond(snapshot)
            }
        }
    }
}

private fun parseMode(raw: String): AnswerMode = AnswerMode.entries.firstOrNull {
    it.wireName.equals(raw.trim(), ignoreCase = true)
} ?: throw IllegalArgumentException("Режим должен быть without_rag или with_rag.")

internal data class ErrorResponse(val error: String)
internal data class InfoResponse(
    val chatModel: String,
    val embeddingModel: String,
    val stats: List<docindex.StrategyStats>,
    val controlQuestions: Int,
)
internal data class QuestionsResponse(val questions: List<ControlQuestion>)
internal data class AnswerRequest(val question: String = "", val mode: String = "with_rag", val topK: Int = 5)
internal data class CompareRequest(val question: String = "", val topK: Int = 5)
internal data class StartEvaluationRequest(val topK: Int = 5)
