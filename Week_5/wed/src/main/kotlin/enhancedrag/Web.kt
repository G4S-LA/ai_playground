package enhancedrag

import docindex.StrategyStats
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
import ragagent.ControlQuestion
import ragagent.ControlQuestionRepository
import ragagent.RagConfig

internal fun runWeb(service: EnhancedRagService, questions: ControlQuestionRepository, config: RagConfig) {
    println("Enhanced RAG UI: http://${config.host}:${config.port}")
    println("LLM: ${service.modelDescription}")
    println("Embeddings: ${service.embeddingDescription}")
    embeddedServer(Netty, host = config.host, port = config.port) {
        enhancedRagModule(service, questions)
    }.start(wait = true)
}

internal fun Application.enhancedRagModule(
    service: EnhancedRagService,
    questions: ControlQuestionRepository,
    registry: RetrievalEvaluationRegistry = RetrievalEvaluationRegistry(service, questions.questions),
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
            applicationLog.error("Unhandled enhanced RAG error", error)
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
                        chatModel = service.modelDescription,
                        embeddingModel = service.embeddingDescription,
                        stats = service.stats(),
                        controlQuestions = questions.questions.size,
                        defaults = RetrievalSettings(),
                    ),
                )
            }
            get("/questions") { call.respond(QuestionsResponse(questions.questions)) }
            post("/index") { call.respond(service.rebuildStructuredIndex()) }
            post("/compare") {
                val request = call.receive<CompareRequest>()
                call.respond(service.compare(request.question, request.settings()))
            }
            post("/evaluations") {
                val request = call.receive<EvaluationRequest>()
                call.respond(HttpStatusCode.Accepted, registry.start(request.settings()))
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

internal data class ErrorResponse(val error: String)
internal data class InfoResponse(
    val chatModel: String,
    val embeddingModel: String,
    val stats: List<StrategyStats>,
    val controlQuestions: Int,
    val defaults: RetrievalSettings,
)
internal data class QuestionsResponse(val questions: List<ControlQuestion>)
internal data class CompareRequest(
    val question: String = "",
    val candidateK: Int = 12,
    val finalK: Int = 5,
    val similarityThreshold: Double = 0.35,
) {
    fun settings() = RetrievalSettings(candidateK, finalK, similarityThreshold).validated()
}
internal data class EvaluationRequest(
    val candidateK: Int = 12,
    val finalK: Int = 5,
    val similarityThreshold: Double = 0.35,
) {
    fun settings() = RetrievalSettings(candidateK, finalK, similarityThreshold).validated()
}
