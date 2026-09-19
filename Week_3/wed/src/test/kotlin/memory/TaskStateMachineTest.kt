package memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskStateMachineTest {
    private val machine = TaskStateMachine()

    @Test
    fun `main path reaches terminal done state`() {
        var state = machine.start(TaskStage.PLANNING, "Составить план")
        state = machine.transition(state, "execution", 1, "Реализовать первый шаг")
        assertEquals("execution", state.stage)
        state = machine.transition(state, "validation", 2, "Проверить результат")
        assertEquals("validation", state.stage)
        state = machine.transition(state, "done", 3, "Задача завершена")

        val view = machine.view(state)
        assertEquals("done", view.stage)
        assertTrue(view.planningApplied)
        assertTrue(view.allowedTransitions.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            machine.transition(state, "execution", 4, "Изменить завершённую задачу")
        }
    }

    @Test
    fun `only declared transitions and controlled returns are allowed`() {
        val idle = TaskState.initial()
        assertFailsWith<IllegalArgumentException> {
            machine.transition(idle, "done", 1, "Завершить без работы")
        }
        val planning = machine.transition(idle, "planning", 1, "Составить план")
        assertFailsWith<IllegalArgumentException> {
            machine.transition(planning, "validation", 1, "Перепрыгнуть этап")
        }
        assertFailsWith<IllegalArgumentException> {
            machine.transition(planning, "done", 1, "Завершить без выполнения")
        }

        val execution = machine.transition(planning, "execution", 1, "Писать код")
        val replanning = machine.transition(execution, "planning", 2, "Уточнить план")
        assertEquals("planning", replanning.stage)

        val validating = machine.transition(
            machine.transition(replanning, "execution", 3, "Исправить код"),
            "validation",
            4,
            "Повторить тесты",
        )
        val retry = machine.transition(validating, "execution", 5, "Исправить замечания")
        assertEquals("execution", retry.stage)

        val direct = machine.start(TaskStage.EXECUTION, "Ответить сразу")
        assertEquals(false, machine.view(direct).planningApplied)
    }

    @Test
    fun `step and expected action are validated`() {
        val state = machine.start(TaskStage.PLANNING, "Составить план")
        assertFailsWith<IllegalArgumentException> {
            machine.transition(state, "execution", 0, "Действие")
        }
        assertFailsWith<IllegalArgumentException> { machine.start(TaskStage.PLANNING, " ") }
        assertFailsWith<IllegalArgumentException> { machine.start(TaskStage.DONE, "Завершить") }
        val updated = machine.transition(state, "execution", 2, "  Выполнить план  ")
        assertEquals(2, updated.currentStep)
        assertEquals("Выполнить план", updated.expectedAction)
    }
}
