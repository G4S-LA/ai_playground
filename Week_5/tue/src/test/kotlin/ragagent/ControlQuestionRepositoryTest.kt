package ragagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlQuestionRepositoryTest {
    @Test
    fun `dataset contains ten complete controls for Monday corpus`() {
        val controls = ControlQuestionRepository().questions

        assertEquals((1..10).toList(), controls.map { it.id })
        assertTrue(controls.all { it.question.isNotBlank() })
        assertTrue(controls.all { it.expectation.isNotBlank() })
        assertTrue(controls.all { it.expectedSources.isNotEmpty() })
        assertTrue(controls.all { it.expectedSections.isNotEmpty() })
        assertTrue(controls.all { it.requiredTerms.isNotEmpty() })
        assertEquals(
            setOf("00-introduction.md", "02-context-engineering.md", "03-memory-and-rag.md"),
            controls.flatMap { it.expectedSources }.toSet(),
        )
    }
}
