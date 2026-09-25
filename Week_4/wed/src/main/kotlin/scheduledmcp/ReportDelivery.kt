package scheduledmcp

import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal fun interface ReportDelivery {
    fun deliver(report: ExecutedReport)
}

internal class ConsoleAndWebhookDelivery(
    private val webhookUrl: String?,
    private val gson: Gson = Gson(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) : ReportDelivery {
    override fun deliver(report: ExecutedReport) {
        println("\n=== ${report.due.schedule.title} ===")
        println(report.report)
        println("=== run ${report.due.run.id} · stories ${report.storiesCount} ===\n")

        val target = webhookUrl ?: return
        val body = gson.toJson(
            mapOf(
                "scheduleId" to report.due.schedule.id,
                "runId" to report.due.run.id,
                "title" to report.due.schedule.title,
                "report" to report.report,
                "storiesCount" to report.storiesCount,
            ),
        )
        val request = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Webhook вернул HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
    }
}
