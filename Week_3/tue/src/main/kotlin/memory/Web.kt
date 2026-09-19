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
import io.ktor.server.routing.put
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
                call.respond(CategoriesResponse(MemoryCategories.asMap()))
            }
            get("/sessions") {
                call.respond(SessionsResponse(agent.listSessions()))
            }
            get("/profiles") {
                call.respond(ProfilesResponse(agent.listProfiles()))
            }
            post("/profiles") {
                val request = call.receive<ProfileRequest>()
                call.respond(HttpStatusCode.Created, ProfileResponse(agent.createProfile(request)))
            }
            route("/profiles/{profileId}") {
                put {
                    val request = call.receive<ProfileRequest>()
                    call.respond(ProfileResponse(agent.updateProfile(call.profileId(), request)))
                }
                delete {
                    agent.deleteProfile(call.profileId())
                    call.respond(mapOf("deleted" to true))
                }
            }
            post("/sessions") {
                val request = call.receive<CreateSessionRequest>()
                call.respond(
                    HttpStatusCode.Created,
                    CreateSessionResponse(agent.createSession(request.profileId ?: UserProfiles.DEFAULT_ID)),
                )
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
                    call.respond(agent.reply(call.sessionId(), request.message.orEmpty()))
                }
                put("/profile") {
                    val request = call.receive<SelectProfileRequest>()
                    call.respond(SnapshotResponse(agent.selectProfile(call.sessionId(), request.profileId.orEmpty())))
                }
                post("/memories") {
                    val request = call.receive<RememberRequest>()
                    val result = agent.remember(
                        call.sessionId(),
                        request.layer.orEmpty(),
                        request.category.orEmpty(),
                        request.content.orEmpty(),
                    )
                    call.respond(HttpStatusCode.Created, result)
                }
                delete("/memories/{layer}/{memoryId}") {
                    val layer = requireNotNull(call.parameters["layer"])
                    val memoryId = requireNotNull(call.parameters["memoryId"])
                    call.respond(SnapshotResponse(agent.forget(call.sessionId(), layer, memoryId)))
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.sessionId(): String =
    requireNotNull(parameters["id"]) { "Не указан идентификатор диалога." }

private fun io.ktor.server.application.ApplicationCall.profileId(): String =
    requireNotNull(parameters["profileId"]) { "Не указан идентификатор профиля." }
