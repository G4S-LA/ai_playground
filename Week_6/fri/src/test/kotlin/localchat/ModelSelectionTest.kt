package localchat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelSelectionTest {
    @Test
    fun `uses configured model when it is installed`() {
        assertEquals("second:latest", selectModel("second", listOf("first:latest", "second:latest")))
    }

    @Test
    fun `falls back to an installed model when configured model is absent`() {
        assertEquals("first:latest", selectModel("missing:latest", listOf("first:latest", "second:latest")))
    }

    @Test
    fun `reports no model only when ollama list is empty`() {
        assertNull(selectModel(null, emptyList()))
    }
}
