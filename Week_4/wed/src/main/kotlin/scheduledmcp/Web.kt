package scheduledmcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
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
import java.time.Instant

internal fun runWeb(agent: NewsAgent, delivery: ReportDelivery, config: AppConfig) {
    val controller = LiveNewsWebController(agent, delivery, config.model)
    val chats = JsonWebChatStore(config.webChatDataFile)
    val runner = WebReportRunner(controller, onReports = chats::addReports)
    runner.start()
    println("Новостной агент: http://${config.webHost}:${config.webPort}")
    try {
        embeddedServer(Netty, host = config.webHost, port = config.webPort) {
            newsAgentWebModule(controller, chats, runner)
        }.start(wait = true)
    } finally {
        runner.close()
    }
}

internal fun Application.newsAgentWebModule(
    controller: NewsWebController,
    chats: JsonWebChatStore,
    runner: WebRunCoordinator,
) {
    val applicationLog = environment.log
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный запрос."))
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled request error", error)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error.message ?: "Внутренняя ошибка сервера."),
            )
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
            get("/sessions") {
                call.respond(SessionsResponse(chats.list()))
            }
            post("/sessions") {
                call.respond(CreateSessionResponse(chats.create()))
            }
            route("/sessions/{id}") {
                get {
                    call.respond(SessionResponse(chats.get(call.sessionId())))
                }
                delete {
                    chats.delete(call.sessionId())
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/messages") {
                    val message = call.receive<MessageRequest>().message.trim()
                    require(message.isNotEmpty()) { "Введите сообщение агенту." }
                    val conversation = chats.get(call.sessionId()).messages.map {
                        AgentMessage(role = it.role, content = it.content)
                    }
                    chats.addMessage(call.sessionId(), "user", message)
                    val scheduled = controller.schedule(message, conversation)
                    val session = chats.addMessage(
                        id = call.sessionId(),
                        role = "assistant",
                        content = scheduled.answer,
                        scheduleId = scheduled.schedule.id,
                    )
                    runner.scheduleChanged()
                    call.respond(MessageResponse(session, scheduled.schedule))
                }
            }
            get("/dashboard") {
                call.respond(
                    WebDashboardResponse(
                        model = controller.modelName,
                        now = Instant.now().toString(),
                        schedules = controller.schedules(),
                        history = controller.history(),
                    ),
                )
            }
            delete("/dashboard") {
                val cleared = controller.clearData()
                runner.scheduleChanged()
                call.respond(ClearDataResponse(cleared))
            }
            post("/run-due") {
                call.respond(WebRunResponse(runner.runNow()))
            }
            get("/events") {
                call.respond(runner.events())
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.sessionId(): String =
    parameters["id"]?.takeIf(String::isNotBlank) ?: error("Не указан идентификатор диалога.")
