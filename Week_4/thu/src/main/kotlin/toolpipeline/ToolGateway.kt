package toolpipeline

internal interface ToolGateway {
    suspend fun <T> withSession(block: suspend ToolSession.() -> T): T
}

internal interface ToolSession {
    suspend fun listTools(): List<AgentTool>
    suspend fun callTool(name: String, arguments: Map<String, Any?>): String
}
