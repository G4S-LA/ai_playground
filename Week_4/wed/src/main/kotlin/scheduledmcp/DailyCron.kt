package scheduledmcp

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

internal data class DailyCron(
    val minute: Int,
    val hour: Int,
) {
    fun nextAfter(after: Instant, zone: ZoneId): Instant {
        val local = after.atZone(zone)
        var candidate = local.toLocalDate().atTime(hour, minute).atZone(zone)
        if (!candidate.toInstant().isAfter(after)) candidate = candidate.plusDays(1)
        return candidate.toInstant()
    }

    companion object {
        fun parse(source: String): DailyCron {
            val parts = source.trim().split(Regex("\\s+"))
            require(parts.size == 5 && parts.drop(2).all { it == "*" }) {
                "Поддерживается ежедневный cron в формате 'минута час * * *', например '0 18 * * *'."
            }
            val minute = parts[0].toIntOrNull()
                ?: throw IllegalArgumentException("Минута cron должна быть числом.")
            val hour = parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("Час cron должен быть числом.")
            require(minute in 0..59) { "Минута cron должна быть от 0 до 59." }
            require(hour in 0..23) { "Час cron должен быть от 0 до 23." }
            return DailyCron(minute, hour)
        }
    }
}
