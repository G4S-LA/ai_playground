package memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskStateMachineTest {
    private val machine = TaskStateMachine()

    @Test
    fun `implementation cannot start before plan approval`() {
        val planning = accepted(TaskState.initial(), TaskEvent.START_TASK, "Составить план")

        val rejected = machine.dispatch(
            planning,
            TaskEvent.COMPLETE_EXECUTION,
            "Попытаться перейти к проверке",
        )

        assertIs<TransitionResult.Rejected>(rejected)
        assertSame(planning, rejected.state)
        assertTrue(rejected.reason.contains("до утверждения плана"))
        assertEquals(listOf("plan_ready", "block", "fail"), rejected.allowedEvents)

        val ready = accepted(planning, TaskEvent.PLAN_READY, "Утвердить план")
        val execution = accepted(ready, TaskEvent.APPROVE_PLAN, "Выполнить утверждённый план")
        assertEquals("execution", execution.stage)
        assertTrue(execution.planningApplied)
        assertTrue(execution.planApproved)
    }

    @Test
    fun `done cannot be reached without validation`() {
        val planning = accepted(TaskState.initial(), TaskEvent.START_TASK, "Составить план")
        val ready = accepted(planning, TaskEvent.PLAN_READY, "Утвердить план")
        val execution = accepted(ready, TaskEvent.APPROVE_PLAN, "Выполнить план")

        val skippedValidation = machine.dispatch(
            execution,
            TaskEvent.PASS_VALIDATION,
            "Пометить готовым",
        )
        assertIs<TransitionResult.Rejected>(skippedValidation)
        assertSame(execution, skippedValidation.state)
        assertTrue(skippedValidation.reason.contains("без этапа validation"))

        val validation = accepted(execution, TaskEvent.COMPLETE_EXECUTION, "Проверить ответ")
        val done = accepted(validation, TaskEvent.PASS_VALIDATION, "Ответ готов")
        assertEquals("done", done.stage)
        assertEquals(setOf(TaskEvent.START_TASK), machine.allowedEvents(done))
    }

    @Test
    fun `failed validation returns to execution and retry exhaustion blocks task`() {
        val planning = accepted(TaskState.initial(), TaskEvent.START_TASK, "Составить план")
        val ready = accepted(planning, TaskEvent.PLAN_READY, "Утвердить план")
        val execution = accepted(ready, TaskEvent.APPROVE_PLAN, "Выполнить план")
        val firstValidation = accepted(execution, TaskEvent.COMPLETE_EXECUTION, "Проверить")
        val revision = accepted(firstValidation, TaskEvent.REJECT_VALIDATION, "Исправить замечания")
        assertEquals("execution", revision.stage)
        assertEquals(1, revision.validationAttempts)

        val secondValidation = accepted(revision, TaskEvent.COMPLETE_EXECUTION, "Проверить повторно")
        val blocked = accepted(
            secondValidation,
            TaskEvent.EXHAUST_VALIDATION,
            "Ожидать уточнение",
            "Проверка повторно нашла неполноту",
        )
        assertEquals("blocked", blocked.stage)
        assertEquals(2, blocked.validationAttempts)
        assertEquals("execution", blocked.recoveryStage)
        assertEquals("Проверка повторно нашла неполноту", blocked.lastFailure)

        val resumed = accepted(blocked, TaskEvent.RESUME, "Продолжить после уточнения")
        assertEquals("execution", resumed.stage)
        assertEquals(0, resumed.validationAttempts)
        assertNull(resumed.lastFailure)
    }

    @Test
    fun `technical failure is explicit and can be retried from failed stage`() {
        val planning = accepted(TaskState.initial(), TaskEvent.START_TASK, "Составить план")
        val ready = accepted(planning, TaskEvent.PLAN_READY, "Утвердить план")
        val execution = accepted(ready, TaskEvent.APPROVE_PLAN, "Выполнить план")
        val failed = accepted(execution, TaskEvent.FAIL, "Повторить попытку", "Модель недоступна")

        assertEquals("failed", failed.stage)
        assertEquals("execution", failed.recoveryStage)
        assertEquals("Модель недоступна", failed.lastFailure)

        val retry = accepted(failed, TaskEvent.RETRY, "Повторить выполнение")
        assertEquals("execution", retry.stage)
        assertNull(retry.lastFailure)
        assertTrue(retry.planApproved)
    }

    private fun accepted(
        state: TaskState,
        event: TaskEvent,
        action: String,
        reason: String? = null,
    ): TaskState {
        val result = machine.dispatch(state, event, action, reason)
        assertIs<TransitionResult.Accepted>(result)
        return result.state
    }
}
