package memorychat

import docindex.StrategyStats
import enhancedrag.RetrievalSettings
import groundedrag.GroundedRagService
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import ragagent.RagConfig

internal fun runWeb(
    chat: MemoryChatService,
    grounded: GroundedRagService,
    config: RagConfig,
) {
    println("RAG memory chat: http://${config.host}:${config.port}")
    embeddedServer(Netty, host = config.host, port = config.port) {
        memoryChatModule(chat, grounded)
    }.start(wait = true)
}

internal fun Application.memoryChatModule(
    chat: MemoryChatService,
    grounded: GroundedRagService,
) {
    val applicationLog = environment.log
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный запрос."))
        }
        exception<NoSuchElementException> { call, error ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error.message ?: "Чат не найден."))
        }
        exception<IllegalStateException> { call, error ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(error.message ?: "Сервис недоступен."))
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный JSON."))
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled memory chat error", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Внутренняя ошибка сервера."))
        }
    }
    routing {
        get("/") {
            val html = checkNotNull(javaClass.classLoader.getResource("web/index.html")) { "web/index.html was not found" }.readText()
            call.respondText(html, ContentType.Text.Html)
        }
        staticResources("/static", "web/static")
        route("/api") {
            get("/info") {
                call.respond(
                    InfoResponse(
                        chatModel = grounded.modelDescription,
                        embeddingModel = grounded.embeddingDescription,
                        stats = grounded.stats(),
                        defaults = RetrievalSettings(),
                    ),
                )
            }
            post("/index") { call.respond(grounded.rebuildStructuredIndex()) }
            post("/chats") { call.respond(HttpStatusCode.Created, chat.create()) }
            get("/chats/{id}") { call.respond(chat.get(chatId(call.parameters["id"]))) }
            delete("/chats/{id}") {
                val deleted = chat.delete(chatId(call.parameters["id"]))
                if (deleted) call.respond(HttpStatusCode.NoContent) else call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Чат не найден."),
                )
            }
            post("/chats/{id}/messages") {
                val request = call.receive<MessageRequest>()
                call.respond(chat.send(chatId(call.parameters["id"]), request.message, request.settings()))
            }
        }
    }
}

private fun chatId(raw: String?): String = requireNotNull(raw) { "Идентификатор чата не указан." }

internal data class ErrorResponse(val error: String)
internal data class InfoResponse(
    val chatModel: String,
    val embeddingModel: String,
    val stats: List<StrategyStats>,
    val defaults: RetrievalSettings,
)

internal data class MessageRequest(
    val message: String = "",
    val candidateK: Int = 12,
    val finalK: Int = 5,
    val similarityThreshold: Double = 0.35,
) {
    fun settings() = RetrievalSettings(candidateK, finalK, similarityThreshold).validated()
}
