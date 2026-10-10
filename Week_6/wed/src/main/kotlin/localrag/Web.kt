package localrag

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

fun runWeb(service: LocalRagService, config: LocalRagConfig) {
    println("Полностью локальный RAG: http://${config.host}:${config.port}")
    println("Embeddings: ${service.embeddingModelDescription}")
    println("Generation: ${service.chatModelDescription}")
    embeddedServer(Netty, host = config.host, port = config.port) {
        localRagModule(service, config)
    }.start(wait = true)
}

fun Application.localRagModule(service: LocalRagService, config: LocalRagConfig) {
    val applicationLog = environment.log
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorPayload(error.message ?: "Некорректный запрос."))
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorPayload(error.message ?: "Некорректный JSON."))
        }
        exception<LocalModelException> { call, error ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorPayload(error.message ?: "Ollama недоступна."))
        }
        exception<IllegalStateException> { call, error ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorPayload(error.message ?: "Локальный pipeline недоступен."))
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled local RAG error", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorPayload("Внутренняя ошибка сервера."))
        }
    }

    routing {
        get("/") {
            val html = checkNotNull(javaClass.classLoader.getResource("web/index.html")) {
                "Не найден web/index.html"
            }.readText()
            call.respondText(html, ContentType.Text.Html)
        }
        staticResources("/static", "web/static")

        route("/api") {
            get("/info") {
                val status = service.modelStatus()
                call.respond(
                    InfoResponse(
                        ollamaUrl = config.ollamaUrl,
                        chatModel = config.chatModel,
                        embeddingModel = config.embeddingModel,
                        chatModelInstalled = status.hasModel(config.chatModel),
                        embeddingModelInstalled = status.hasModel(config.embeddingModel),
                        status = status,
                        stats = service.stats(),
                    ),
                )
            }
            post("/index") {
                call.respond(service.rebuildIndex())
            }
            post("/ask") {
                val request = call.receive<AskRequest>()
                call.respond(service.answer(request.question, request.topK))
            }
        }
    }
}

data class ErrorPayload(val error: String)
data class AskRequest(val question: String = "", val topK: Int = 5)
data class InfoResponse(
    val ollamaUrl: String,
    val chatModel: String,
    val embeddingModel: String,
    val chatModelInstalled: Boolean,
    val embeddingModelInstalled: Boolean,
    val status: OllamaStatus,
    val stats: List<docindex.StrategyStats>,
)
