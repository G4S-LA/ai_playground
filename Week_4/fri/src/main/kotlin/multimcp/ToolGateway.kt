package multimcp

internal interface ToolGateway {
    suspend fun <T> withSession(block: suspend ToolSession.() -> T): T
}

internal interface ToolSession {
    fun listServers(): List<McpServerInfo>
    fun listTools(): List<AgentTool>
    suspend fun callTool(name: String, arguments: Map<String, Any?>): String
}
