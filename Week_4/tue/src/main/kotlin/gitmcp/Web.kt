package gitmcp

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

internal fun runWeb(agent: ChatAgent, config: AppConfig) {
    println("Web chat: http://${config.webHost}:${config.webPort}")
    embeddedServer(Netty, host = config.webHost, port = config.webPort) {
        chatModule(agent)
    }.start(wait = true)
}

internal fun Application.chatModule(agent: ChatAgent) {
    val applicationLog = environment.log
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid request."))
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid JSON."))
        }
        exception<AgentException> { call, error ->
            call.respond(HttpStatusCode.BadGateway, ErrorResponse(error.message ?: "Agent error."))
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled request error", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error."))
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
            get("/sessions") {
                call.respond(SessionsResponse(agent.listSessions()))
            }
            post("/sessions") {
                call.respond(HttpStatusCode.Created, CreateSessionResponse(agent.createSession()))
            }
            route("/sessions/{id}") {
                get {
                    call.respond(SnapshotResponse(agent.snapshot(call.sessionId())))
                }
                delete {
                    agent.deleteSession(call.sessionId())
                    call.respond(mapOf("deleted" to true))
                }
                post("/messages") {
                    val request = call.receive<MessageRequest>()
                    val reply = agent.reply(call.sessionId(), request.message)
                    call.respond(
                        MessageResponse(
                            answer = reply.answer,
                            toolExecutions = reply.toolExecutions,
                            snapshot = agent.snapshot(call.sessionId()),
                        ),
                    )
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.sessionId(): String =
    requireNotNull(parameters["id"]) { "Chat identifier is missing." }
