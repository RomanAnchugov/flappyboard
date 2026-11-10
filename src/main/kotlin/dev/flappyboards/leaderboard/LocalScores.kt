package dev.flappyboards.leaderboard

import com.intellij.ide.util.PropertiesComponent

/**
 * Offline fallback storage: keeps a local top list on this machine so the
 * leaderboard screen is meaningful even without a configured Supabase backend.
 */
object LocalScores {

    private const val KEY = "dev.flappyboards.localScores"
    private const val MAX = 100

    fun top(limit: Int = 20): List<LeaderboardService.Entry> =
        PropertiesComponent.getInstance().getValue(KEY, "")
            .lines()
            .mapNotNull { line ->
                val parts = line.split(':')
                if (parts.size < 2) return@mapNotNull null
                val nick = parts[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val score = parts[1].toIntOrNull() ?: return@mapNotNull null
                val accuracy = parts.getOrNull(2)?.toIntOrNull() ?: 0
                LeaderboardService.Entry(nick, score, accuracy)
            }
            .sortedByDescending { it.score }
            .take(limit)

    fun add(nickname: String, score: Int, accuracy: Int = 0) {
        if (score <= 0) return
        val all = (top(MAX) + LeaderboardService.Entry(nickname, score, accuracy))
            .sortedByDescending { it.score }
            .take(MAX)
        PropertiesComponent.getInstance()
            .setValue(KEY, all.joinToString("\n") { "${it.nickname}:${it.score}:${it.accuracy}" })
    }
}
