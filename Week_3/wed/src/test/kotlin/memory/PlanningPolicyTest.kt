package memory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanningPolicyTest {
    private val policy = RequestComplexityPolicy()

    @Test
    fun `simple conversational requests go directly to execution`() {
        assertFalse(policy.needsPlanning("Привет!"))
        assertFalse(policy.needsPlanning("Что такое краткосрочная память?"))
        assertFalse(policy.needsPlanning("Переведи слово memory"))
    }

    @Test
    fun `multi-step and implementation requests require a plan`() {
        assertTrue(policy.needsPlanning("Составь план миграции приложения"))
        assertTrue(policy.needsPlanning("Реализуй CLI и web-интерфейс для агента"))
        assertTrue(policy.needsPlanning("- Добавить API\n- Написать тесты"))
    }
}
