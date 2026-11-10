package dev.flappyboards.game

import com.intellij.ui.JBColor
import dev.flappyboards.leaderboard.LeaderboardService
import dev.flappyboards.leaderboard.LocalScores
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The original flat-minimalism vector renderer, ported as-is from GamePanel.
 * Selected via Settings -> FlappyBrains -> Graphics: Classic.
 */
class VectorRenderer : GameRenderer {

    override fun paint(g: Graphics2D, s: GameSnapshot, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g2.color = C.BG
        g2.fillRect(0, 0, width, height)

        // Fit the logical world into the panel, centered.
        val W = s.worldW
        val H = s.worldH
        val scale = min(width / W, height / H)
        val ox = (width - W * scale) / 2f
        val oy = (height - H * scale) / 2f
        val shake = s.shakeT / 0.3f
        val sx = if (s.shakeT > 0) (Math.random() - 0.5) * 14f * shake else 0.0
        val sy = if (s.shakeT > 0) (Math.random() - 0.5) * 14f * shake else 0.0
        g2.translate(ox + sx, oy + sy)
        g2.scale(scale.toDouble(), scale.toDouble())
        g2.clip(Rectangle(0, 0, W.toInt(), H.toInt()))

        drawClouds(g2, s)
        drawPipes(g2, s)
        drawGround(g2, s)
        drawBird(g2, s)

        when (s.state) {
            GameState.MENU -> drawMenu(g2, s)
            GameState.PLAYING -> {
                drawScore(g2, s)
                drawKeyPrompt(g2, s)
            }
            GameState.PAUSED -> {
                drawScore(g2, s)
                drawKeyPrompt(g2, s)
                drawPaused(g2, s)
            }
            GameState.GAME_OVER -> drawGameOver(g2, s)
            GameState.LEADERBOARD -> drawLeaderboard(g2, s)
        }

        if (s.flashT > 0) {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (s.flashT / 0.22f) * 0.35f)
            g2.color = Color.WHITE
            g2.fillRect(0, 0, W.toInt(), H.toInt())
        }

        g2.dispose()
    }

    private fun drawClouds(g2: Graphics2D, s: GameSnapshot) {
        g2.color = C.CLOUD
        for (c in s.clouds) {
            val w = 70f * c.scale
            val h = 24f * c.scale
            g2.fill(Ellipse2D.Float(c.x, c.y, w, h))
            g2.fill(Ellipse2D.Float(c.x + w * 0.45f, c.y - h * 0.35f, w * 0.7f, h * 0.9f))
            g2.fill(Ellipse2D.Float(c.x + w * 0.8f, c.y + h * 0.15f, w * 0.5f, h * 0.7f))
        }
    }

    private fun drawPipes(g2: Graphics2D, s: GameSnapshot) {
        for (p in s.pipes) {
            val topH = p.gapY - p.gapH / 2
            val botY = p.gapY + p.gapH / 2
            g2.color = C.PIPE
            g2.fill(RoundRectangle2D.Float(p.x, -12f, s.pipeW, topH + 12f, 10f, 10f))
            g2.fill(RoundRectangle2D.Float(p.x, botY, s.pipeW, s.worldH - botY, 10f, 10f))
            g2.color = C.PIPE_DARK
            g2.fill(RoundRectangle2D.Float(p.x - 4f, topH - 14f, s.pipeW + 8f, 14f, 6f, 6f))
            g2.fill(RoundRectangle2D.Float(p.x - 4f, botY, s.pipeW + 8f, 14f, 6f, 6f))
        }
    }

    private fun drawGround(g2: Graphics2D, s: GameSnapshot) {
        val top = (s.worldH - s.groundH).toInt()
        g2.color = C.GROUND
        g2.fillRect(0, top, s.worldW.toInt(), s.groundH.toInt())
        g2.color = C.PIPE_DARK
        var x = -s.groundScroll
        while (x < s.worldW) {
            g2.fillRect(x.toInt(), top + 16, 18, 5)
            x += 40f
        }
        g2.color = C.PIPE
        g2.fillRect(0, top - 3, s.worldW.toInt(), 3)
    }

    private fun drawBird(g2: Graphics2D, s: GameSnapshot) {
        val bob = if (s.state == GameState.MENU || s.state == GameState.LEADERBOARD) sin(s.menuT * 2.4).toFloat() * 5f else 0f
        val y = s.birdY + bob
        val r = s.birdR

        g2.translate(s.birdX.toDouble(), y.toDouble())
        g2.rotate(s.birdRot.toDouble())

        g2.color = C.BIRD
        g2.fill(Ellipse2D.Float(-r, -r, r * 2, r * 2))
        g2.color = C.BIRD_DARK
        g2.fill(Ellipse2D.Float(-r, 2f, r * 2, r))

        val wy = -s.wingT * 8f
        g2.fill(Ellipse2D.Float(-r - 2f, wy - 2f, 15f, 10f))

        g2.color = Color.WHITE
        g2.fill(Ellipse2D.Float(2f, -9f, 9f, 9f))
        g2.color = Color(30, 31, 34)
        g2.fill(Ellipse2D.Float(4.5f, -7.5f, 4f, 4f))

        g2.color = C.BEAK
        val beak = Path2D.Float().apply {
            moveTo(r - 2f, -1f)
            lineTo(r + 9f, 2f)
            lineTo(r - 2f, 5f)
            closePath()
        }
        g2.fill(beak)

        g2.color = C.BIRD_DARK
        g2.draw(Ellipse2D.Float(-r, -r, r * 2, r * 2))

        g2.rotate(-s.birdRot.toDouble())
        g2.translate(-s.birdX.toDouble(), -y.toDouble())
    }

    private fun drawScore(g2: Graphics2D, s: GameSnapshot) {
        g2.font = mono(40f * (1f + s.scorePop * 0.35f))
        g2.color = C.TEXT
        val text = s.score.toString()
        val w = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, (s.worldW - w) / 2f, 64f)
    }

    private fun drawKeyPrompt(g2: Graphics2D, s: GameSnapshot) {
        val size = 64f
        val x = (s.worldW - size) / 2f
        val y = 84f

        g2.color = C.CARD
        g2.fill(RoundRectangle2D.Float(x, y, size, size, 12f, 12f))

        if (s.keyFlashT > 0) {
            val a = s.keyFlashT.coerceIn(0f, 1f) * 0.55f
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a)
            g2.color = if (s.keyFlashOk) C.OK else C.DANGER
            g2.fill(RoundRectangle2D.Float(x, y, size, size, 12f, 12f))
            g2.composite = AlphaComposite.SrcOver
        }

        g2.font = mono(34f)
        val label = s.requiredChar.toString()
        val w = g2.fontMetrics.stringWidth(label)
        val tx = x + (size - w) / 2f
        val ty = y + (size + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2f
        g2.color = C.TEXT
        g2.drawString(label, tx, ty)

        g2.font = mono(10f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "PRESS", s.worldW / 2f, y - 8f)
    }

    private fun drawMenu(g2: Graphics2D, s: GameSnapshot) {
        g2.font = mono(34f)
        g2.color = C.TEXT
        drawCentered(g2, "FLAPPY", s.worldW / 2f, 150f)
        g2.color = C.ACCENT
        drawCentered(g2, "BOARDS", s.worldW / 2f, 192f)

        g2.font = mono(16f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "BEST  ${s.best}", s.worldW / 2f, 236f)

        val alpha = (0.55f + 0.45f * sin(s.menuT * 3.2f).toFloat()).coerceIn(0f, 1f)
        val oldComposite = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        g2.font = mono(18f)
        g2.color = C.TEXT
        drawCentered(g2, "CLICK or SPACE to start", s.worldW / 2f, 330f)
        g2.composite = oldComposite

        g2.font = mono(13f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "then type the shown key to flap", s.worldW / 2f, 368f)
        drawCentered(g2, "L  leaderboard      O  settings", s.worldW / 2f, 396f)

        if (s.nicknameBlank) {
            drawCentered(g2, "set your nickname in settings to compete", s.worldW / 2f, 430f)
        }
    }

    private fun drawPaused(g2: Graphics2D, s: GameSnapshot) {
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f)
        g2.color = Color.BLACK
        g2.fillRect(0, 0, s.worldW.toInt(), s.worldH.toInt())
        g2.composite = AlphaComposite.SrcOver
        g2.font = mono(26f)
        g2.color = C.TEXT
        drawCentered(g2, "PAUSED", s.worldW / 2f, s.worldH / 2f - 10f)
        g2.font = mono(14f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "click or SPACE to resume", s.worldW / 2f, s.worldH / 2f + 24f)
    }

    private fun drawGameOver(g2: Graphics2D, s: GameSnapshot) {
        val cardW = 250f
        val cardH = 156f
        val cx = (s.worldW - cardW) / 2f
        val cy = 140f

        g2.color = C.CARD
        g2.fill(RoundRectangle2D.Float(cx, cy, cardW, cardH, 16f, 16f))

        g2.font = mono(22f)
        g2.color = C.DANGER
        drawCentered(g2, "GAME OVER", s.worldW / 2f, cy + 36f)

        g2.font = mono(16f)
        g2.color = C.TEXT
        drawCentered(g2, "SCORE  ${s.score}", s.worldW / 2f, cy + 66f)
        drawCentered(g2, "BEST   ${s.best}", s.worldW / 2f, cy + 90f)

        val totalKeys = s.keysOk + s.keysWrong
        val accuracy = if (totalKeys > 0) s.keysOk * 100 / totalKeys else 100
        g2.color = if (accuracy >= 90) C.OK else C.TEXT_DIM
        drawCentered(g2, "ACC    $accuracy%", s.worldW / 2f, cy + 114f)

        if (s.newBest) {
            val alpha = (0.6f + 0.4f * sin(s.menuT * 6f).toFloat()).coerceIn(0f, 1f)
            val oldComposite = g2.composite
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g2.color = C.ACCENT
            drawCentered(g2, "NEW BEST!", s.worldW / 2f, cy + 140f)
            g2.composite = oldComposite
        }

        g2.font = mono(13f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "SPACE  retry      L  leaderboard", s.worldW / 2f, cy + cardH + 28f)
        drawCentered(g2, "O  settings      ESC  menu", s.worldW / 2f, cy + cardH + 50f)

        if (LeaderboardService.isConfigured && s.publishScores && s.nicknameBlank) {
            g2.color = C.ACCENT
            drawCentered(g2, "add a nickname (O) to enter the leaderboard", s.worldW / 2f, cy + cardH + 80f)
        }
    }

    private fun drawLeaderboard(g2: Graphics2D, s: GameSnapshot) {
        g2.font = mono(24f)
        g2.color = C.TEXT
        drawCentered(g2, "LEADERBOARD", s.worldW / 2f, 64f)

        when {
            s.lbLoading -> {
                g2.font = mono(15f)
                g2.color = C.TEXT_DIM
                drawCentered(g2, "loading...", s.worldW / 2f, 150f)
            }
            s.lbRows.isNotEmpty() -> {
                g2.font = mono(15f)
                var y = 112f
                for ((i, e) in s.lbRows.withIndex()) {
                    g2.color = if (i < 3) C.ACCENT else C.TEXT
                    g2.drawString("${(i + 1).toString().padStart(2, ' ')}. ${e.nickname.take(18)}", 34f, y)
                    val sw = g2.fontMetrics.stringWidth(e.score.toString())
                    g2.drawString(e.score.toString(), s.worldW - 34f - sw, y)
                    y += 23f
                }
            }
            else -> {
                val local = LocalScores.top(10)
                if (s.lbError != null) {
                    g2.font = mono(14f)
                    g2.color = C.DANGER
                    drawCentered(g2, s.lbError!!.take(42), s.worldW / 2f, 150f)
                }
                g2.font = mono(15f)
                if (local.isEmpty()) {
                    g2.color = C.TEXT_DIM
                    drawCentered(g2, if (s.lbError != null) "local best: ${s.best}" else "no scores yet — go play!", s.worldW / 2f, 190f)
                } else {
                    var y = if (s.lbError != null) 195f else 130f
                    for ((i, e) in local.withIndex()) {
                        g2.color = C.TEXT
                        g2.drawString("${i + 1}. ${e.nickname.take(18)}", 34f, y)
                        val sw = g2.fontMetrics.stringWidth(e.score.toString())
                        g2.drawString(e.score.toString(), s.worldW - 34f - sw, y)
                        y += 23f
                    }
                }
            }
        }

        g2.font = mono(13f)
        g2.color = C.TEXT_DIM
        if (!LeaderboardService.isConfigured) {
            drawCentered(g2, "local mode — configure Supabase (see README)", s.worldW / 2f, s.worldH - 64f)
        }
        drawCentered(g2, "ESC — back", s.worldW / 2f, s.worldH - 40f)
    }

    private fun drawCentered(g2: Graphics2D, text: String, xCenter: Float, y: Float) {
        val w = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, xCenter - w / 2f, y)
    }

    private fun mono(sizeF: Float): Font =
        Font(Font.MONOSPACED, Font.BOLD, sizeF.roundToInt())

    /** Theme-aware palette: (dark UI, light UI). */
    private object C {
        val BG = JBColor(Color(0x1B1D22), Color(0xF5F6F8))
        val GROUND = JBColor(Color(0x2A2D33), Color(0xE2E5EA))
        val CLOUD = JBColor(Color(0x272A31), Color(0xE4E7EB))
        val CARD = JBColor(Color(0x24272D), Color(0xFFFFFF))
        val PIPE = JBColor(Color(0x4FA05A), Color(0x4FA05A))
        val PIPE_DARK = JBColor(Color(0x3B7D45), Color(0x3B7D45))
        val BIRD = JBColor(Color(0xFFC94D), Color(0xFFB300))
        val BIRD_DARK = JBColor(Color(0xE3A82E), Color(0xC98A00))
        val BEAK = Color(0xFF8A3D)
        val TEXT = JBColor.foreground()
        val TEXT_DIM = JBColor(Color(0x9DA0A8), Color(0x7A7F87))
        val ACCENT = JBColor(Color(0x6CB6FF), Color(0x1B7AD8))
        val OK = JBColor(Color(0x77D785), Color(0x2E9E44))
        val DANGER = JBColor(Color(0xFF7A84), Color(0xD4444F))
    }
}
