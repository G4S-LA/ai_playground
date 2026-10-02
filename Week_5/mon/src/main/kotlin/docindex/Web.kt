package docindex

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

internal fun runWeb(service: IndexingService, documentStore: UploadedDocumentStore, config: AppConfig) {
    println("Document Index UI: http://${config.host}:${config.port}")
    println("Embeddings: ${service.embeddingDescription}")
    embeddedServer(Netty, host = config.host, port = config.port) {
        documentIndexWebModule(service, documentStore)
    }.start(wait = true)
}

internal fun Application.documentIndexWebModule(
    service: IndexingService,
    documentStore: UploadedDocumentStore,
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
            applicationLog.error("Unhandled document index request error", error)
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
                call.respond(InfoResponse(service.embeddingDescription, service.stats()))
            }
            post("/documents") {
                call.respond(HttpStatusCode.Created, documentStore.save(call.receive<UploadRequest>()))
            }
            post("/index") {
                val request = call.receive<BuildIndexRequest>()
                val strategies = if (request.strategy.equals("both", ignoreCase = true)) {
                    ChunkStrategy.entries
                } else {
                    listOf(ChunkStrategy.parse(request.strategy))
                }
                val results = strategies.map { strategy ->
                    service.build(
                        strategy = strategy,
                        fixedSize = request.fixedSize,
                        overlap = request.overlap,
                        structuredMaxSize = request.structuredMaxSize,
                    )
                }
                call.respond(BuildIndexResponse(results, service.stats()))
            }
            post("/search") {
                val request = call.receive<SearchRequest>()
                val strategy = ChunkStrategy.parse(request.strategy)
                call.respond(
                    SearchResponse(
                        request.query.trim(),
                        strategy.wireName,
                        service.search(request.query, strategy, request.topK),
                    ),
                )
            }
            post("/compare") {
                val request = call.receive<CompareRequest>()
                call.respond(service.compare(request.query, request.topK))
            }
        }
    }
}

internal data class ErrorResponse(val error: String)
internal data class InfoResponse(val embeddingProvider: String, val stats: List<StrategyStats>)
internal data class BuildIndexRequest(
    val strategy: String = "both",
    val fixedSize: Int = 1_500,
    val overlap: Int = 250,
    val structuredMaxSize: Int = 2_000,
)
internal data class BuildIndexResponse(val results: List<IndexBuildResult>, val stats: List<StrategyStats>)
internal data class SearchRequest(val query: String = "", val strategy: String = "structured", val topK: Int = 5)
internal data class SearchResponse(val query: String, val strategy: String, val results: List<SearchHit>)
internal data class CompareRequest(val query: String = "", val topK: Int = 5)
