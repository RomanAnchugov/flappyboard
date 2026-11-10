package dev.flappyboards.game

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import dev.flappyboards.leaderboard.LeaderboardService
import dev.flappyboards.leaderboard.LocalScores
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Terminal-style renderer: the whole frame is a character grid drawn with the
 * editor font. From a distance it looks like another terminal panel in the IDE.
 *
 * On pause it turns into a fake Gradle build log (boss screen), so the game
 * disappears from the screen entirely until focus comes back.
 */
class AsciiRenderer : GameRenderer {

    private companion object {
        const val TEXT = 0
        const val DIM = 1
        const val ACCENT = 2
        const val GREEN = 3
        const val DANGER = 4
        const val OK = 5

        const val GUTTER_W = 6      // "  12 │ "
        const val HEADER_ROWS = 1   // terminal title line
    }

    private class Pal(
        val bg: Color,
        val text: Color,
        val dim: Color,
        val accent: Color,
        val green: Color,
        val danger: Color,
        val ok: Color,
    )

    // character buffer
    private var cols = 0
    private var rows = 0
    private var cells = CharArray(0)
    private var colors = IntArray(0)

    // big on-screen glyph for the key prompt (drawn over the buffer after flush)
    private var bigGlyph: Char? = null
    private var bigGlyphX = 0
    private var bigGlyphY = 0
    private var bigGlyphColor = TEXT

    private var font: Font = Font(Font.MONOSPACED, Font.BOLD, 15)
    private var charW = 1f
    private var lineH = 1f
    private var ascent = 0f

    // world -> cell mapping
    private var cellW = 1f
    private var cellH = 1f

    // ------------------------------------------------------------------ frame

    override fun paint(g: Graphics2D, s: GameSnapshot, width: Int, height: Int) {
        val pal = palette()
        setup(g, s, pal, width, height)

        g.color = pal.bg
        g.fillRect(0, 0, width, height)
        clear()

        drawHeader(s)
        drawGutter(s)

        when (s.state) {
            GameState.PAUSED -> drawBossScreen(s)
            else -> {
                drawScene(s)
                drawHud(s)
                when (s.state) {
                    GameState.MENU -> drawMenu(s)
                    GameState.PLAYING, GameState.READY -> {
                        drawKeyBadge(s)
                        if (s.state == GameState.READY && blink(s.menuT)) {
                            putCentered(9, "type the shown key to start", TEXT)
                        }
                    }
                    GameState.GAME_OVER -> drawGameOver(s)
                    GameState.LEADERBOARD -> drawLeaderboard(s)
                    GameState.PAUSED -> Unit
                }
            }
        }

        flush(g, pal)
        drawBigGlyph(g, pal)
    }

    private fun setup(g: Graphics2D, s: GameSnapshot, pal: Pal, width: Int, height: Int) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val fontSize = (height / 34f).roundToInt().coerceIn(11, 17)
        font = Font(scheme.editorFontName, Font.BOLD, fontSize)
        val fm = g.getFontMetrics(font)
        charW = fm.charWidth('M').toFloat().coerceAtLeast(1f)
        lineH = fm.height.toFloat().coerceAtLeast(1f)
        ascent = fm.ascent.toFloat()

        val newCols = (width / charW).toInt().coerceAtLeast(20)
        val newRows = (height / lineH).toInt().coerceAtLeast(10)
        if (newCols != cols || newRows != rows) {
            cols = newCols
            rows = newRows
            cells = CharArray(cols * rows)
            colors = IntArray(cols * rows)
        }
        cellW = s.worldW / (cols - GUTTER_W).coerceAtLeast(1)
        cellH = s.worldH / (rows - HEADER_ROWS).coerceAtLeast(1)
    }

    private fun clear() {
        java.util.Arrays.fill(cells, ' ')
        java.util.Arrays.fill(colors, TEXT)
        bigGlyph = null
    }

    // ------------------------------------------------------------ buffer ops

    private fun put(x: Int, y: Int, ch: Char, color: Int) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return
        cells[y * cols + x] = ch
        colors[y * cols + x] = color
    }

    private fun putString(x: Int, y: Int, text: String, color: Int) {
        for ((i, c) in text.withIndex()) put(x + i, y, c, color)
    }

    private fun putCentered(y: Int, text: String, color: Int) {
        putString(cols / 2 - text.length / 2, y, text, color)
    }

    private fun frame(x: Int, y: Int, w: Int, h: Int, color: Int) {
        put(x, y, '┌', color)
        put(x + w - 1, y, '┐', color)
        put(x, y + h - 1, '└', color)
        put(x + w - 1, y + h - 1, '┘', color)
        for (i in 1 until w - 1) {
            put(x + i, y, '─', color)
            put(x + i, y + h - 1, '─', color)
        }
        for (j in 1 until h - 1) {
            put(x, y + j, '│', color)
            put(x + w - 1, y + j, '│', color)
        }
    }

    private fun colOf(worldX: Float): Int = GUTTER_W + (worldX / cellW).toInt()
    private fun rowOf(worldY: Float): Int = HEADER_ROWS + (worldY / cellH).toInt()

    private fun blink(t: Float, period: Float = 1.1f): Boolean = (t % period) < period * 0.62f

    // ------------------------------------------------------------- terminal

    private fun drawHeader(s: GameSnapshot) {
        for (x in 0 until cols) put(x, 0, '─', DIM)
        putString(2, 0, "flappyboards — zsh", DIM)
        val dims = "${cols - GUTTER_W}×${rows - HEADER_ROWS}"
        putString(cols - dims.length - 2, 0, dims, DIM)
    }

    private fun drawGutter(s: GameSnapshot) {
        val birdRow = if (s.state == GameState.PLAYING || s.state == GameState.GAME_OVER) {
            rowOf(s.birdY).coerceIn(HEADER_ROWS, rows - 1)
        } else -1
        for (r in HEADER_ROWS until rows) {
            putString(0, r, r.toString().padStart(3), if (r == birdRow) ACCENT else DIM)
            put(4, r, '│', DIM)
        }
    }

    // ----------------------------------------------------------------- scene

    private fun drawScene(s: GameSnapshot) {
        for (cl in s.clouds) {
            val cx = colOf(cl.x + 30f * cl.scale)
            val cy = rowOf(cl.y + 10f * cl.scale)
            put(cx, cy, '~', DIM)
            if (cl.scale > 0.9f) put(cx + 1, cy, '~', DIM)
            if (cl.scale > 1.25f) put(cx + 2, cy - 1, '~', DIM)
        }

        val groundTop = rowOf(s.worldH - s.groundH)
        val pw = (s.pipeW / cellW).roundToInt().coerceAtLeast(2)
        for (p in s.pipes) {
            val x0 = colOf(p.x)
            if (x0 + pw < GUTTER_W || x0 >= cols) continue
            val gapTop = rowOf(p.gapY - p.gapH / 2)
            val gapBot = rowOf(p.gapY + p.gapH / 2)
            for (r in HEADER_ROWS until groundTop) {
                if (r >= gapTop && r < gapBot) continue
                val cap = r == gapTop - 1 || r == gapBot
                val xStart = if (cap) x0 - 1 else x0
                val w = if (cap) pw + 2 else pw
                for (c in maxOf(GUTTER_W, xStart) until minOf(cols, xStart + w)) {
                    put(c, r, '█', GREEN)
                }
            }
        }

        for (r in groundTop until rows) {
            for (c in GUTTER_W until cols) {
                if (r == groundTop) {
                    val tick = (((c - GUTTER_W) + (s.groundScroll * 0.3f).toInt()) % 5) == 0
                    put(c, r, if (tick) '-' else '═', GREEN)
                } else {
                    put(c, r, '▒', DIM)
                }
            }
        }

        // Bigger bird: 3-cell body + animated wing.
        val bx = colOf(s.birdX)
        val by = rowOf(s.birdY)
        val dead = s.state == GameState.GAME_OVER && s.birdRot > 1.4f
        put(bx - 1, by, '(', ACCENT)
        put(bx, by, if (dead) '×' else 'o', ACCENT)
        put(bx + 1, by, '>', ACCENT)
        if (!dead) {
            if (s.wingT > 0.45f) put(bx - 1, by - 1, '\\', ACCENT) // wing up
            else put(bx - 1, by + 1, '/', ACCENT)                  // wing down
        }
    }

    private fun drawHud(s: GameSnapshot) {
        var x = GUTTER_W
        val row = HEADER_ROWS
        putString(x, row, "SCORE ", DIM); x += 6
        putString(x, row, s.score.toString().padStart(4, '0'), if (s.scorePop > 0.2f) ACCENT else TEXT); x += 4
        putString(x, row, "  BEST ", DIM); x += 7
        putString(x, row, s.best.toString().padStart(4, '0'), TEXT); x += 4
        if (s.state == GameState.PLAYING) {
            val total = s.keysOk + s.keysWrong
            val acc = if (total > 0) s.keysOk * 100 / total else 100
            putString(x, row, "  ACC ", DIM); x += 6
            putString(x, row, "$acc%", if (acc >= 90) OK else TEXT)
        }
    }

    /** Large framed prompt with the key the player must type. */
    private fun drawKeyBadge(s: GameSnapshot) {
        val w = 7
        val h = 6
        val x = cols / 2 - w / 2
        val y = 3
        frame(x, y, w, h, DIM)
        putCentered(y + 1, "PRESS", DIM)
        bigGlyph = s.requiredChar
        bigGlyphX = x + w / 2
        bigGlyphY = y + 3
        bigGlyphColor = when {
            s.keyFlashT > 0.05f && s.keyFlashOk -> OK
            s.keyFlashT > 0.05f -> DANGER
            else -> ACCENT
        }
    }

    // --------------------------------------------------------------- screens

    private fun drawMenu(s: GameSnapshot) {
        val boxW = 32
        val boxH = 5
        val bx = cols / 2 - boxW / 2
        val by = 9
        frame(bx, by, boxW, boxH, DIM)
        putCentered(by + 1, "F L A P P Y", TEXT)
        putCentered(by + 2, "B O A R D S", ACCENT)

        putCentered(by + 6, "best ${s.best.toString().padStart(4, '0')}", DIM)
        if (blink(s.menuT)) putCentered(by + 9, "CLICK or SPACE to start", TEXT)
        putCentered(by + 11, "then type the shown key to flap", DIM)
        putCentered(by + 13, "L leaderboard    O settings", DIM)
        putCentered(by + 15, "first key: [ ${s.requiredChar} ]", ACCENT)
        if (s.nicknameBlank) putCentered(by + 17, "set nickname in settings (O) to compete", DIM)
    }

    private fun drawGameOver(s: GameSnapshot) {
        val w = 30
        val h = 8
        val x = cols / 2 - w / 2
        val y = 12
        frame(x, y, w, h, DIM)
        putCentered(y + 1, "GAME OVER", DANGER)
        putCentered(y + 3, "SCORE ${s.score.toString().padStart(4, '0')}", TEXT)
        putCentered(y + 4, "BEST  ${s.best.toString().padStart(4, '0')}", TEXT)

        val total = s.keysOk + s.keysWrong
        val acc = if (total > 0) s.keysOk * 100 / total else 100
        putCentered(y + 5, "ACC   $acc%", if (acc >= 90) OK else DIM)
        if (s.newBest && blink(s.menuT, 0.7f)) putCentered(y + 6, "NEW BEST!", ACCENT)

        putCentered(y + h + 2, "SPACE retry   L leaderboard   ESC menu", DIM)
        putCentered(y + h + 3, "O settings", DIM)
        putCentered(y + h + 5, "next key: [ ${s.requiredChar} ]", ACCENT)
        if (s.nicknameBlank) putCentered(y + h + 6, "set nickname in settings (O) to compete", DIM)
    }

    private fun drawLeaderboard(s: GameSnapshot) {
        // tabs: ALL-TIME / WEEK (switch with ←/→)
        val allActive = s.lbScope == LeaderboardService.Scope.ALL_TIME
        val left = if (allActive) "[ALL-TIME]" else " ALL-TIME "
        val right = if (allActive) " WEEK " else "[WEEK]"
        val tabs = "$left   $right"
        val tx = cols / 2 - tabs.length / 2
        putString(tx, 3, left, if (allActive) ACCENT else DIM)
        putString(tx + left.length + 3, 3, right, if (!allActive) ACCENT else DIM)
        putCentered(4, "←/→ switch", DIM)

        when {
            s.lbLoading -> putCentered(7, "loading...", DIM)
            s.lbRows.isNotEmpty() -> {
                val ownInTop = s.lbRows.any { it.nickname.equals(s.myNick, ignoreCase = true) }
                for ((i, e) in s.lbRows.withIndex()) {
                    val own = e.nickname.equals(s.myNick, ignoreCase = true)
                    val color = when {
                        own -> OK
                        i < 3 -> ACCENT
                        else -> TEXT
                    }
                    val line = "%2d. %-13s %6d %3d%% %3s".format(
                        i + 1, e.nickname.take(13), e.score, e.accuracy, e.ageLabel()
                    )
                    putString(GUTTER_W + 1, 6 + i, line, color)
                }
                if (!ownInTop && s.lbMyPlace != null) {
                    putCentered(rows - 6, "... your place: #${s.lbMyPlace}", ACCENT)
                }
            }
            else -> {
                var y = 7
                if (s.lbError != null) {
                    putCentered(y, s.lbError!!.take((cols - GUTTER_W - 2).coerceAtLeast(8)), DANGER)
                    y += 2
                }
                val local = LocalScores.top(10)
                if (local.isEmpty()) {
                    putCentered(y + 1, if (s.lbError != null) "local best ${s.best}" else "no scores yet — go play!", DIM)
                } else {
                    for ((i, e) in local.withIndex()) {
                        val color = if (e.nickname.equals(s.myNick, ignoreCase = true)) OK else TEXT
                        val line = "%2d. %-13s %6d %3d%%".format(i + 1, e.nickname.take(13), e.score, e.accuracy)
                        putString(GUTTER_W + 1, y + 1 + i, line, color)
                    }
                }
            }
        }
        if (!LeaderboardService.isConfigured) {
            putCentered(rows - 5, "local mode — configure Supabase (README)", DIM)
        }
        putCentered(rows - 3, "ESC — back", DIM)
    }

    /** Fake Gradle output shown while paused — the boss screen. */
    private fun drawBossScreen(s: GameSnapshot) {
        val x0 = GUTTER_W
        putString(x0, 2, "Executing Gradle tasks...", TEXT)

        // Stable pseudo-random content per ~2.4s window.
        val rnd = Random((s.menuT / 2.4f).toInt())
        val tasks = listOf(
            ":compileKotlin", ":compileJava", ":processResources", ":jarSearchableOptions",
            ":verifyPlugin", ":buildPlugin", ":test", ":dependencyCheck", ":spotlessApply", ":instrumentCode",
        )
        val statuses = listOf("UP-TO-DATE", "FROM-CACHE", "NO-SOURCE", "")
        val n = minOf(7 + rnd.nextInt(4), rows - 8)
        var r = 4
        for (i in 0 until n) {
            val task = tasks[rnd.nextInt(tasks.size)]
            val status = statuses[rnd.nextInt(statuses.size)]
            val line = "> Task $task $status".trimEnd()
            putString(x0, r + i, line, if (status == "UP-TO-DATE") DIM else TEXT)
        }

        val p = ((s.menuT * 23f).toInt() % 89) + 4
        val barW = minOf(34, cols - GUTTER_W - 2)
        val filled = (barW * p / 100).coerceIn(0, barW - 1)
        val sb = StringBuilder("[")
        repeat(filled) { sb.append('=') }
        if (filled < barW - 1) sb.append('>')
        repeat((barW - filled - 1).coerceAtLeast(0)) { sb.append('.') }
        sb.append("] ").append(p).append("%")
        putString(x0, r + n + 1, sb.toString(), ACCENT)
    }

    // ----------------------------------------------------------------- paint

    /** Draws the required key as a large glyph (2x+ line height) at the badge position. */
    private fun drawBigGlyph(g: Graphics2D, pal: Pal) {
        val ch = bigGlyph ?: return
        val f = font.deriveFont(lineH * 2.3f)
        g.font = f
        g.color = colorFor(pal, bigGlyphColor)
        val fm = g.getFontMetrics(f)
        val cx = bigGlyphX * charW + charW / 2f
        val cy = bigGlyphY * lineH + lineH / 2f
        val str = ch.toString()
        g.drawString(str, cx - fm.stringWidth(str) / 2f, cy + (fm.ascent - fm.descent) / 2f)
    }

    private fun flush(g: Graphics2D, pal: Pal) {
        g.font = font
        for (r in 0 until rows) {
            var c0 = 0
            while (c0 < cols) {
                val color = colors[r * cols + c0]
                var c1 = c0
                while (c1 < cols && colors[r * cols + c1] == color) c1++
                val str = String(cells, r * cols + c0, c1 - c0)
                if (str.isNotBlank()) {
                    g.color = colorFor(pal, color)
                    g.drawString(str, c0 * charW, r * lineH + ascent)
                }
                c0 = c1
            }
        }
    }

    private fun colorFor(pal: Pal, color: Int): Color = when (color) {
        DIM -> pal.dim
        ACCENT -> pal.accent
        GREEN -> pal.green
        DANGER -> pal.danger
        OK -> pal.ok
        else -> pal.text
    }

    private fun palette(): Pal {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val fg = scheme.defaultForeground
        return Pal(
            bg = scheme.defaultBackground,
            text = fg,
            dim = Color(fg.red, fg.green, fg.blue, 105),
            accent = JBColor(Color(0xE8B45A), Color(0xA9740B)),
            green = JBColor(Color(0x7FA57C), Color(0x4E8A4C)),
            danger = JBColor(Color(0xE07077), Color(0xC03A44)),
            ok = JBColor(Color(0xA3CE92), Color(0x3F8C4A)),
        )
    }
}
