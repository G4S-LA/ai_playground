package multimcp

internal const val NEWS_SERVER = "news"
internal const val CURRENCY_SERVER = "currency"
internal const val EXCEL_SERVER = "excel"

internal const val GET_TOP_NEWS_TOOL = "get_top_news"
internal const val GET_RATES_TOOL = "get_rates"
internal const val CREATE_WORKBOOK_TOOL = "create_workbook"
internal const val WRITE_EXCEL_TOOL = "write_data_to_excel"
internal const val CREATE_TABLE_TOOL = "create_table"
internal const val INSPECT_WORKBOOK_TOOL = "get_workbook_metadata"

internal val MCP_SERVERS = listOf(
    McpServerInfo(
        id = NEWS_SERVER,
        name = "News MCP",
        description = "Существующий сервер среды: получает свежие Hacker News.",
        transport = "stdio",
        source = "Week_4/wed",
    ),
    McpServerInfo(
        id = CURRENCY_SERVER,
        name = "Frankfurter MCP",
        description = "Готовый hosted MCP со справочными валютными курсами.",
        transport = "Streamable HTTP",
        source = "mcp.frankfurter.dev",
    ),
    McpServerInfo(
        id = EXCEL_SERVER,
        name = "Excel MCP",
        description = "Готовый community MCP для создания и проверки .xlsx.",
        transport = "stdio",
        source = "ralscha/excel-mcp v1.1.2",
    ),
)

internal val DEMO_FLOW_ORDER = listOf(
    GET_TOP_NEWS_TOOL,
    GET_RATES_TOOL,
    CREATE_WORKBOOK_TOOL,
    WRITE_EXCEL_TOOL,
    CREATE_TABLE_TOOL,
    INSPECT_WORKBOOK_TOOL,
)
