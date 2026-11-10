package dev.flappyboards.leaderboard

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

/**
 * REST client for the Supabase-backed global leaderboard.
 *
 * The publishable/anon key is public by design; the database is protected by
 * RLS policies and sanity checks inside the `submit_score` RPC (see
 * supabase/schema.sql).
 */
object LeaderboardService {

    const val SUPABASE_URL = "https://dimwoakpknpriazhislo.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_FIO90a_0dZpu2ZXbhWhBnQ_pTwVdQwf"

    val isConfigured: Boolean
        get() = !SUPABASE_URL.contains("YOUR_PROJECT") && !SUPABASE_ANON_KEY.startsWith("YOUR_")

    enum class Scope(val view: String, val weekly: Boolean) {
        ALL_TIME("leaderboard", false),
        WEEK("leaderboard_week", true)
    }

    data class Entry(
        val nickname: String,
        val score: Int,
        val accuracy: Int = 0,
        val lastPlayed: String = "",
    ) {
        /** Short relative age like "now", "42m", "3d", "2mo" ("" if unknown). */
        fun ageLabel(): String = try {
            val played = OffsetDateTime.parse(lastPlayed)
            val minutes = Duration.between(played.toInstant(), OffsetDateTime.now().toInstant()).toMinutes()
            when {
                minutes < 1L -> "now"
                minutes < 60L -> "${minutes}m"
                minutes < 60L * 24 -> "${minutes / 60}h"
                minutes < 60L * 24 * 30 -> "${minutes / 60 / 24}d"
                else -> "${minutes / 60 / 24 / 30}mo"
            }
        } catch (e: Exception) {
            ""
        }
    }

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val nicknameRegex = Regex("\"nickname\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val scoreRegex = Regex("\"best_score\"\\s*:\\s*(\\d+)")
    private val accuracyRegex = Regex("\"accuracy\"\\s*:\\s*(\\d+)")
    private val lastPlayedRegex = Regex("\"last_played\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** Fetches the global top-N for the given scope. The callback runs on the EDT. */
    fun fetchTop(scope: Scope, limit: Int = 20, onResult: (Result<List<Entry>>) -> Unit) {
        if (!isConfigured) {
            onResult(Result.failure(IllegalStateException("Leaderboard is not configured")))
            return
        }
        CompletableFuture.runAsync {
            try {
                val url = "$SUPABASE_URL/rest/v1/${scope.view}" +
                    "?select=nickname,best_score,accuracy,last_played" +
                    "&order=best_score.desc&limit=$limit"
                val request = baseRequest(url)
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

    /**
     * Rank of the player in the given scope, even outside the top-N.
     * `null` means the player has no runs there yet. Callback runs on the EDT.
     */
    fun fetchMyPlace(nickname: String, scope: Scope, onResult: (Result<Int?>) -> Unit) {
        if (!isConfigured) {
            onResult(Result.failure(IllegalStateException("Leaderboard is not configured")))
            return
        }
        CompletableFuture.runAsync {
            try {
                val body = """{"p_nickname":${jsonEscape(nickname)},"p_weekly":${scope.weekly}}"""
                val request = baseRequest("$SUPABASE_URL/rest/v1/rpc/my_place")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    error("HTTP ${response.statusCode()}")
                }
                // RPC returns a JSON number or null.
                val place = response.body().trim().toIntOrNull()
                SwingUtilities.invokeLater { onResult(Result.success(place)) }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { onResult(Result.failure(e)) }
            }
        }
    }

    /** Submits a score via the `submit_score` RPC (server-side checks). */
    fun submitScore(
        nickname: String,
        score: Int,
        accuracy: Int,
        deviceId: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        if (!isConfigured) {
            onResult(Result.failure(IllegalStateException("Leaderboard is not configured")))
            return
        }
        CompletableFuture.runAsync {
            try {
                val body = """{"p_nickname":${jsonEscape(nickname)},"p_score":$score,""" +
                    """"p_accuracy":${accuracy.coerceIn(0, 100)},"p_device_id":"$deviceId"}"""
                val request = baseRequest("$SUPABASE_URL/rest/v1/rpc/submit_score")
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

    private fun baseRequest(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
            .header("Accept", "application/json")

    /**
     * Minimal JSON parsing for the known fields of our own endpoint.
     * Avoids pulling a JSON library into the plugin.
     */
    private fun parseRows(body: String): List<Entry> =
        body.split("},{").mapNotNull { chunk ->
            val nickname = nicknameRegex.find(chunk)?.groupValues?.get(1)?.unescapeJson()
            val score = scoreRegex.find(chunk)?.groupValues?.get(1)?.toIntOrNull()
            if (nickname == null || score == null) return@mapNotNull null
            val accuracy = accuracyRegex.find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val lastPlayed = lastPlayedRegex.find(chunk)?.groupValues?.get(1)?.unescapeJson() ?: ""
            Entry(nickname, score, accuracy, lastPlayed)
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
