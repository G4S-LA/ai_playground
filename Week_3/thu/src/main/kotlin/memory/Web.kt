package memory

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

fun runWeb(agent: MemoryAgent, config: AppConfig) {
    println("Веб-интерфейс: http://${config.webHost}:${config.webPort}")
    embeddedServer(Netty, host = config.webHost, port = config.webPort) {
        memoryModule(agent)
    }.start(wait = true)
}

fun Application.memoryModule(agent: MemoryAgent) {
    val applicationLog = environment.log
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный запрос."))
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный JSON."))
        }
        exception<AgentException> { call, error ->
            call.respond(HttpStatusCode.BadGateway, ErrorResponse(error.message ?: "Ошибка модели."))
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled request error", error)
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
            get("/categories") {
                call.respond(CategoriesResponse(MemoryCategories.asMap(), InvariantCategories.all()))
            }
            get("/sessions") { call.respond(SessionsResponse(agent.listSessions())) }
            post("/sessions") {
                call.respond(HttpStatusCode.Created, CreateSessionResponse(agent.createSession()))
            }
            route("/sessions/{id}") {
                get { call.respond(SnapshotResponse(agent.snapshot(call.sessionId()))) }
                delete {
                    agent.deleteSession(call.sessionId())
                    call.respond(mapOf("deleted" to true))
                }
                post("/messages") {
                    val request = call.receive<MessageRequest>()
                    call.respond(agent.reply(call.sessionId(), request.message))
                }
                post("/memories") {
                    val request = call.receive<RememberRequest>()
                    call.respond(
                        HttpStatusCode.Created,
                        agent.remember(call.sessionId(), request.layer, request.category, request.content),
                    )
                }
                delete("/memories/{layer}/{memoryId}") {
                    val layer = requireNotNull(call.parameters["layer"])
                    val memoryId = requireNotNull(call.parameters["memoryId"])
                    call.respond(SnapshotResponse(agent.forget(call.sessionId(), layer, memoryId)))
                }
                post("/invariants") {
                    val request = call.receive<InvariantRequest>()
                    call.respond(
                        HttpStatusCode.Created,
                        agent.addInvariant(call.sessionId(), request.category, request.content),
                    )
                }
                delete("/invariants/{invariantId}") {
                    val invariantId = requireNotNull(call.parameters["invariantId"])
                    call.respond(SnapshotResponse(agent.removeInvariant(call.sessionId(), invariantId)))
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.sessionId(): String =
    requireNotNull(parameters["id"]) { "Не указан идентификатор диалога." }
