package docindex

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTest {
    @Test
    fun `web flow uploads builds and compares indexes`() = testApplication {
        val root = createTempDirectory("document-index-web-test")
        val documents = root.resolve("documents")
        val service = IndexingService(
            loader = DocumentLoader(documents),
            embeddings = DemoEmbeddingProvider(),
            index = SqliteVectorIndex(root.resolve("index.db")),
        )
        application { documentIndexWebModule(service, UploadedDocumentStore(documents)) }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("Document Index" in page.body<String>())

        val markdown = "# Install\nRun the project with Gradle.\n\n# Storage\nVectors live in SQLite."
        val encoded = Base64.getEncoder().encodeToString(markdown.toByteArray())
        val upload = client.post("/api/documents") {
            contentType(ContentType.Application.Json)
            setBody("""{"files":[{"name":"guide.md","base64":"$encoded"}]}""")
        }
        assertEquals(HttpStatusCode.Created, upload.status)

        val build = client.post("/api/index") {
            contentType(ContentType.Application.Json)
            setBody("""{"strategy":"both","fixedSize":300,"overlap":30,"structuredMaxSize":300}""")
        }
        assertEquals(HttpStatusCode.OK, build.status)
        assertTrue("structured" in build.body<String>())

        val compare = client.post("/api/compare") {
            contentType(ContentType.Application.Json)
            setBody("""{"query":"SQLite vectors","topK":3}""")
        }
        assertEquals(HttpStatusCode.OK, compare.status)
        val body = compare.body<String>()
        assertTrue("guide.md" in body)
        assertTrue("Storage" in body)
    }
}
