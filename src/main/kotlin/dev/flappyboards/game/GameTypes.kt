package dev.flappyboards.game

import dev.flappyboards.leaderboard.LeaderboardService
import java.awt.Graphics2D

enum class GameState { MENU, PLAYING, PAUSED, GAME_OVER, LEADERBOARD }

class PipeData(val x: Float, val gapY: Float, val gapH: Float)
class CloudData(var x: Float, val y: Float, val scale: Float, val speedFactor: Float)

/** Everything a renderer needs to draw one frame. Immutable snapshot of the game. */
class GameSnapshot(
    val state: GameState,
    // world metrics (constant)
    val worldW: Float,
    val worldH: Float,
    val birdX: Float,
    val birdR: Float,
    val pipeW: Float,
    val groundH: Float,
    // bird
    val birdY: Float,
    val birdRot: Float,
    val wingT: Float,
    // scene
    val pipes: List<PipeData>,
    val clouds: List<CloudData>,
    val groundScroll: Float,
    // hud
    val score: Int,
    val best: Int,
    // typing mechanic
    val requiredChar: Char,
    val keyFlashT: Float,
    val keyFlashOk: Boolean,
    val keysOk: Int,
    val keysWrong: Int,
    // cosmetics
    val menuT: Float,
    val shakeT: Float,
    val flashT: Float,
    val scorePop: Float,
    val newBest: Boolean,
    // leaderboard screen
    val lbRows: List<LeaderboardService.Entry>,
    val lbLoading: Boolean,
    val lbError: String?,
    // settings used by screens
    val nicknameBlank: Boolean,
    val publishScores: Boolean,
)

/** A renderer draws one full frame from a snapshot: background, scene and overlays. */
interface GameRenderer {
    fun paint(g: Graphics2D, s: GameSnapshot, width: Int, height: Int)
}
