package dev.flappyboards.leaderboard

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

/**
 * REST client for the Supabase-backed global leaderboard.
 *
 * Replace [SUPABASE_URL] / [SUPABASE_ANON_KEY] with your project values
 * (see README.md). While they hold placeholders the plugin runs in local
 * mode: scores are stored only on this machine via [LocalScores].
 */
object LeaderboardService {

    const val SUPABASE_URL = "https://YOUR_PROJECT.supabase.co"
    const val SUPABASE_ANON_KEY = "YOUR_SUPABASE_ANON_KEY"

    val isConfigured: Boolean
        get() = !SUPABASE_URL.contains("YOUR_PROJECT") && !SUPABASE_ANON_KEY.startsWith("YOUR_")

    data class Entry(val nickname: String, val score: Int)

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val nicknameRegex = Regex("\"nickname\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val scoreRegex = Regex("\"best_score\"\\s*:\\s*(\\d+)")

    /** Fetches the global top-N asynchronously. The callback runs on the EDT. */
    fun fetchTop(limit: Int = 20, onResult: (Result<List<Entry>>) -> Unit) {
        if (!isConfigured) {
            onResult(Result.failure(IllegalStateException("Leaderboard is not configured")))
            return
        }
        CompletableFuture.runAsync {
            try {
                val url =
                    "$SUPABASE_URL/rest/v1/leaderboard?select=nickname,best_score&order=best_score.desc&limit=$limit"
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .header("Accept", "application/json")
                    .GET()
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    error("HTTP ${response.statusCode()}")
                }
                val rows = parseRows(response.body()).take(limit)
                SwingUtilities.invokeLater { onResult(Result.success(rows)) }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { onResult(Result.failure(e)) }
            }
        }
    }

    /** Submits a score via the `submit_score` RPC (server-side sanity checks). */
    fun submitScore(nickname: String, score: Int, onResult: (Result<Unit>) -> Unit) {
        if (!isConfigured) {
            onResult(Result.failure(IllegalStateException("Leaderboard is not configured")))
            return
        }
        CompletableFuture.runAsync {
            try {
                val body = """{"p_nickname":${jsonEscape(nickname)},"p_score":$score}"""
                val request = HttpRequest.newBuilder(URI.create("$SUPABASE_URL/rest/v1/rpc/submit_score"))
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    error("HTTP ${response.statusCode()}: ${response.body().take(200)}")
                }
                SwingUtilities.invokeLater { onResult(Result.success(Unit)) }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { onResult(Result.failure(e)) }
            }
        }
    }

    /**
     * Minimal JSON parsing for the two known fields of our own endpoint.
     * Avoids pulling a JSON library into the plugin.
     */
    private fun parseRows(body: String): List<Entry> =
        body.split("},{").mapNotNull { chunk ->
            val nickname = nicknameRegex.find(chunk)?.groupValues?.get(1)?.unescapeJson()
            val score = scoreRegex.find(chunk)?.groupValues?.get(1)?.toIntOrNull()
            if (nickname != null && score != null) Entry(nickname, score) else null
        }

    private fun String.unescapeJson(): String =
        replace("\\\"", "\"").replace("\\\\", "\\")

    private fun jsonEscape(value: String): String = buildString {
        append('"')
        for (c in value) {
            if (c == '"' || c == '\\') append('\\')
            append(c)
        }
        append('"')
    }
}
