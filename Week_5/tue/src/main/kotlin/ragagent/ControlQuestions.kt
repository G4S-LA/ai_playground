package ragagent

import com.google.gson.Gson

class ControlQuestionRepository(private val gson: Gson = Gson()) {
    val questions: List<ControlQuestion> by lazy {
        val json = checkNotNull(javaClass.classLoader.getResource("evaluation/questions.json")) {
            "evaluation/questions.json was not found"
        }.readText()
        gson.fromJson(json, Array<ControlQuestion>::class.java).toList().also { loaded ->
            check(loaded.size == 10) { "Контрольный набор должен содержать ровно 10 вопросов." }
            check(loaded.map { it.id }.distinct().size == loaded.size) { "ID контрольных вопросов должны быть уникальны." }
        }
    }
}
