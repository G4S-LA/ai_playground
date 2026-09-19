package memory

fun interface PlanningPolicy {
    fun needsPlanning(message: String): Boolean
}

class RequestComplexityPolicy : PlanningPolicy {
    private val explicitPlanning = Regex(
        """(план|спланир|по шагам|поэтапно|архитектур|\bplan\b|step by step|architecture)""",
        RegexOption.IGNORE_CASE,
    )
    private val implementation = Regex(
        """(реализ|разработ|спроект|проанализ|исслед|сравн|созда|добав|измен|исправ|""" +
            """\bimplement\b|\bdevelop\b|\bdesign\b|\banaly[sz]e\b|\bresearch\b|\bcompare\b|\bcreate\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val technicalArtifact = Regex(
        """(\bcli\b|\bweb\b|\bapi\b|приложен|проект|сервис|сайт|интерфейс|модел|""" +
            """баз[ау] данных|архитектур|код|\bagent\b|агент)""",
        RegexOption.IGNORE_CASE,
    )
    private val sequence = Regex(
        """\b(сначала|затем|после этого|несколько|одновременно|и ещё|and then|after that|multiple)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val listItem = Regex("(?m)^\\s*(?:[-*]|\\d+[.)])\\s+")

    override fun needsPlanning(message: String): Boolean {
        val normalized = message.trim()
        val listItems = listItem.findAll(normalized).count()
        return normalized.length >= 240 ||
            listItems >= 2 ||
            explicitPlanning.containsMatchIn(normalized) ||
            sequence.containsMatchIn(normalized) ||
            (implementation.containsMatchIn(normalized) && technicalArtifact.containsMatchIn(normalized))
    }
}
