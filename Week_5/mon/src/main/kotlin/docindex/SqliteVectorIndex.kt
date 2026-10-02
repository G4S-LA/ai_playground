package docindex

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.math.sqrt

class SqliteVectorIndex(private val databasePath: Path) {
    init {
        databasePath.parent?.createDirectories()
        Class.forName("org.sqlite.JDBC")
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS chunks (
                        chunk_id TEXT PRIMARY KEY,
                        strategy TEXT NOT NULL,
                        source TEXT NOT NULL,
                        title TEXT NOT NULL,
                        section TEXT NOT NULL,
                        content_type TEXT NOT NULL,
                        chunk_index INTEGER NOT NULL,
                        start_position INTEGER NOT NULL,
                        end_position INTEGER NOT NULL,
                        token_count INTEGER NOT NULL,
                        page INTEGER,
                        text TEXT NOT NULL,
                        embedding BLOB NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX IF NOT EXISTS chunks_strategy_idx ON chunks(strategy)")
            }
        }
    }

    fun replace(strategy: ChunkStrategy, chunks: List<IndexedChunk>) {
        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM chunks WHERE strategy = ?").use { statement ->
                    statement.setString(1, strategy.wireName)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO chunks(
                        chunk_id, strategy, source, title, section, content_type, chunk_index,
                        start_position, end_position, token_count, page, text, embedding
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    chunks.forEach { indexed ->
                        val chunk = indexed.chunk
                        statement.setString(1, chunk.chunkId)
                        statement.setString(2, chunk.strategy.wireName)
                        statement.setString(3, chunk.source)
                        statement.setString(4, chunk.title)
                        statement.setString(5, chunk.section)
                        statement.setString(6, chunk.contentType.wireName)
                        statement.setInt(7, chunk.chunkIndex)
                        statement.setInt(8, chunk.startPosition)
                        statement.setInt(9, chunk.endPosition)
                        statement.setInt(10, chunk.tokenCount)
                        if (chunk.page == null) statement.setNull(11, java.sql.Types.INTEGER) else statement.setInt(11, chunk.page)
                        statement.setString(12, chunk.text)
                        statement.setBytes(13, indexed.embedding.toBytes())
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    fun search(strategy: ChunkStrategy, queryEmbedding: FloatArray, topK: Int): List<SearchHit> {
        require(topK in 1..50) { "topK должен быть от 1 до 50." }
        val scored = mutableListOf<SearchHit>()
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT chunk_id, source, title, section, content_type, chunk_index,
                       token_count, page, text, embedding
                FROM chunks WHERE strategy = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, strategy.wireName)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val embedding = result.getBytes("embedding").toFloatArray()
                        if (embedding.size != queryEmbedding.size) continue
                        scored += SearchHit(
                            chunkId = result.getString("chunk_id"),
                            source = result.getString("source"),
                            title = result.getString("title"),
                            section = result.getString("section"),
                            contentType = result.getString("content_type"),
                            chunkIndex = result.getInt("chunk_index"),
                            tokenCount = result.getInt("token_count"),
                            page = result.getInt("page").let { if (result.wasNull()) null else it },
                            text = result.getString("text"),
                            score = cosine(queryEmbedding, embedding),
                        )
                    }
                }
            }
        }
        return scored.sortedByDescending { it.score }.take(topK)
    }

    fun stats(): List<StrategyStats> {
        val found = mutableMapOf<String, StrategyStats>()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT strategy, COUNT(DISTINCT source) AS documents, COUNT(*) AS chunks,
                           AVG(LENGTH(text)) AS average_characters, AVG(token_count) AS average_tokens
                    FROM chunks GROUP BY strategy
                    """.trimIndent(),
                ).use { result ->
                    while (result.next()) {
                        val strategy = result.getString("strategy")
                        found[strategy] = StrategyStats(
                            strategy = strategy,
                            documents = result.getInt("documents"),
                            chunks = result.getInt("chunks"),
                            averageCharacters = result.getDouble("average_characters"),
                            averageTokens = result.getDouble("average_tokens"),
                        )
                    }
                }
            }
        }
        return ChunkStrategy.entries.map { strategy ->
            found[strategy.wireName] ?: StrategyStats(strategy.wireName, 0, 0, 0.0, 0.0)
        }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${databasePath}")
}

private fun FloatArray.toBytes(): ByteArray = ByteBuffer.allocate(size * Float.SIZE_BYTES)
    .order(ByteOrder.LITTLE_ENDIAN)
    .also { buffer -> forEach(buffer::putFloat) }
    .array()

private fun ByteArray.toFloatArray(): FloatArray {
    require(size % Float.SIZE_BYTES == 0) { "Повреждённый эмбеддинг в SQLite." }
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / Float.SIZE_BYTES) { buffer.float }
}

private fun cosine(left: FloatArray, right: FloatArray): Float {
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    for (index in left.indices) {
        val a = left[index].toDouble()
        val b = right[index].toDouble()
        dot += a * b
        leftNorm += a * a
        rightNorm += b * b
    }
    if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
    return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat()
}
