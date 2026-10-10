package localopt

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

fun runWeb(service: ComparisonService, config: AppConfig) {
    println("Сравнение локальной модели: http://${config.host}:${config.port}")
    println("Ollama: ${config.ollamaUrl} · модель: ${config.model}")
    embeddedServer(Netty, host = config.host, port = config.port) {
        optimizationModule(service)
    }.start(wait = true)
}

fun Application.optimizationModule(service: ComparisonService) {
    val applicationLog = environment.log
    install(ContentNegotiation) { gson { setPrettyPrinting() } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный запрос."))
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Некорректный JSON."))
        }
        exception<LocalModelException> { call, error ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(error.message ?: "Ollama недоступна."))
        }
        exception<Throwable> { call, error ->
            applicationLog.error("Unhandled optimization error", error)
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
            get("/info") { call.respond(service.info()) }
            post("/compare") {
                val request = call.receive<CompareRequest>()
                call.respond(service.compare(request.prompt))
            }
        }
    }
}
