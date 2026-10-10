package localchat

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

fun runWeb(service: LocalChatService, config: AppConfig) {
    println("Локальный чат: http://${config.webHost}:${config.webPort}")
    println("Ollama: ${service.endpoint} · модель: ${service.modelName}")
    embeddedServer(Netty, host = config.webHost, port = config.webPort) {
        localChatModule(service)
    }.start(wait = true)
}

fun Application.localChatModule(service: LocalChatService) {
    val applicationLog = environment.log
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный запрос."))
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный JSON."))
        }
        exception<NoSuchElementException> { call, error ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error.message ?: "Диалог не найден."))
        }
        exception<ModelUnavailableException> { call, error ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(error.message ?: "Ollama недоступна."))
        }
        exception<ChatStorageException> { call, error ->
            applicationLog.error("SQLite error", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error.message ?: "Ошибка SQLite."))
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled local chat error", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Внутренняя ошибка сервера."))
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
                call.respond(InfoResponse(service.modelName, service.endpoint, service.modelStatus()))
            }
            get("/chats") {
                call.respond(ChatListResponse(service.listChats()))
            }
            post("/chats") {
                call.respond(HttpStatusCode.Created, ChatResponse(service.createChat()))
            }
            route("/chats/{id}") {
                get {
                    call.respond(ChatResponse(service.getChat(call.chatId())))
                }
                delete {
                    service.deleteChat(call.chatId())
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/messages") {
                    val request = call.receive<SendMessageRequest>()
                    call.respond(service.send(call.chatId(), request.message))
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.chatId(): String =
    requireNotNull(parameters["id"]) { "Не указан идентификатор диалога." }

data class ErrorResponse(val error: String)
data class InfoResponse(val model: String, val endpoint: String, val status: ModelStatus)
data class ChatListResponse(val chats: List<ChatSummary>)
data class ChatResponse(val chat: ChatSnapshot)
data class SendMessageRequest(val message: String = "")
