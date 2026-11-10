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
                val sep = line.lastIndexOf(':')
                if (sep <= 0) return@mapNotNull null
                val nick = line.substring(0, sep).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val score = line.substring(sep + 1).toIntOrNull() ?: return@mapNotNull null
                LeaderboardService.Entry(nick, score)
            }
            .sortedByDescending { it.score }
            .take(limit)

    fun add(nickname: String, score: Int) {
        if (score <= 0) return
        val all = (top(MAX) + LeaderboardService.Entry(nickname, score))
            .sortedByDescending { it.score }
            .take(MAX)
        PropertiesComponent.getInstance().setValue(KEY, all.joinToString("\n") { "${it.nickname}:${it.score}" })
    }
}
