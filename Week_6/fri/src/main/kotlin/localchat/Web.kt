package localchat

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest

class UnauthorizedException : RuntimeException("Неверный или отсутствующий Bearer token")

fun runWeb(service: LocalChatService, config: AppConfig) {
    println("Локальный чат: http://${config.host}:${config.port}")
    println("Ollama: ${config.ollamaUrl} · модель: ${config.model ?: "автовыбор"}")
    println("Диалоги: ${config.databasePath}")
    embeddedServer(Netty, host = config.host, port = config.port) {
        localChatModule(service, config.apiKey)
    }.start(wait = true)
}

fun Application.localChatModule(service: LocalChatService, apiKey: String) {
    val applicationLog = environment.log
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<UnauthorizedException> { call, error ->
            call.respondApiError(HttpStatusCode.Unauthorized, error.message.orEmpty(), "unauthorized")
        }
        exception<IllegalArgumentException> { call, error ->
            call.respondApiError(HttpStatusCode.BadRequest, error.message ?: "Некорректный запрос.", "bad_request")
        }
        exception<BadRequestException> { call, error ->
            call.respondApiError(HttpStatusCode.BadRequest, error.message ?: "Некорректный JSON.", "invalid_json")
        }
        exception<NoSuchElementException> { call, error ->
            call.respondApiError(HttpStatusCode.NotFound, error.message ?: "Диалог не найден.", "chat_not_found")
        }
        exception<ModelUnavailableException> { call, error ->
            call.respondApiError(HttpStatusCode.BadGateway, error.message ?: "Ollama недоступна.", "upstream_error")
        }
        exception<ChatStorageException> { call, error ->
            applicationLog.error("Chat storage error", error)
            call.respondApiError(HttpStatusCode.InternalServerError, "Ошибка хранилища диалогов.", "storage_error")
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled local chat error", error)
            call.respondApiError(HttpStatusCode.InternalServerError, "Внутренняя ошибка сервера.", "internal_error")
        }
    }

    routing {
        get("/") { call.respondAsset("index.html", ContentType.Text.Html) }
        get("/app.js") { call.respondAsset("app.js", ContentType.Application.JavaScript) }
        get("/styles.css") { call.respondAsset("styles.css", ContentType.Text.CSS) }
        get("/health") { call.respond(service.health()) }

        route("/api") {
            route("/chats") {
                get {
                    call.requireApiKey(apiKey)
                    call.respond(ChatListResponse(service.listChats()))
                }
                post {
                    call.requireApiKey(apiKey)
                    call.receive<Map<String, Any?>>()
                    call.respond(HttpStatusCode.Created, ChatResponse(service.createChat()))
                }
                route("/{id}") {
                    get {
                        call.requireApiKey(apiKey)
                        call.respond(ChatResponse(service.getChat(call.chatId())))
                    }
                    delete {
                        call.requireApiKey(apiKey)
                        service.deleteChat(call.chatId())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/messages") {
                        call.requireApiKey(apiKey)
                        val request = call.receive<SendMessageRequest>()
                        call.respond(ChatResponse(service.send(call.chatId(), request.message)))
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.chatId(): String =
    requireNotNull(parameters["id"]) { "Не указан идентификатор диалога." }

private fun ApplicationCall.requireApiKey(expectedKey: String) {
    val actual = request.headers[HttpHeaders.Authorization].orEmpty()
    val expected = "Bearer $expectedKey"
    if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) throw UnauthorizedException()
}

private suspend fun ApplicationCall.respondAsset(name: String, contentType: ContentType) {
    val content = checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "Не найден ресурс $name" }
        .use { it.readBytes() }
    respondBytes(content, contentType)
}

private suspend fun ApplicationCall.respondApiError(status: HttpStatusCode, message: String, code: String) {
    respond(status, ApiErrorResponse(ApiErrorDetail(message = message, code = code)))
}
