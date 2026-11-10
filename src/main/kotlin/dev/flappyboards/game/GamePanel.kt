package dev.flappyboards.game

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import dev.flappyboards.leaderboard.LeaderboardService
import dev.flappyboards.leaderboard.LocalScores
import dev.flappyboards.settings.FlappyBoardsConfigurable
import dev.flappyboards.settings.FlappyBoardsSettings
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole game: render + physics + input, drawn on a plain Swing canvas.
 * The logical world is 360x560 and is scaled to fit the tool window, so the
 * physics feel identical at any window size.
 */
class GamePanel(private val project: Project? = null) : JPanel() {

    private enum class State { MENU, PLAYING, PAUSED, GAME_OVER, LEADERBOARD }

    private companion object {
        const val BEST_KEY = "dev.flappyboards.best"

        // Logical world size (game units, not pixels).
        const val W = 360f
        const val H = 560f
        const val GROUND_H = 44f

        // Physics (units per second).
        const val GRAVITY = 1500f
        const val FLAP_VY = -440f
        const val BASE_SPEED = 150f
        const val MAX_SPEED = 255f

        // Bird.
        const val BIRD_X = 92f
        const val BIRD_R = 13f

        // Pipes.
        const val PIPE_W = 62f
        const val SPAWN_SPACING = 225f

        const val TOP_SCORE_LIMIT = 20

        // Typing mechanic: the key pool grows with the score.
        const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
        const val DIGITS = "0123456789"
        const val SYMBOLS = "!@#$%^&*()-_=+[]{};:'\",.<>/?\\|`~"
        const val LETTERS_UNTIL = 10   // letters only below this score
        const val DIGITS_UNTIL = 20    // + digits below this score, then all symbols

        // Light anti-spam: each wrong keypress pushes the bird down a bit.
        const val WRONG_VY_PENALTY = 90f
        const val WRONG_VY_CAP = 560f
    }

    private class Pipe(var x: Float, val gapY: Float, val gapH: Float, var passed: Boolean = false)
    private class Cloud(var x: Float, val y: Float, val scale: Float, val speedFactor: Float)

    // --- game state ---
    private var state = State.MENU
    private var birdY = H * 0.42f
    private var birdVy = 0f
    private var birdRot = 0f
    private var wingT = 0f
    private val pipes = mutableListOf<Pipe>()
    private var score = 0
    private var best = 0
    private var speed = BASE_SPEED
    private var groundScroll = 0f

    // --- cosmetics ---
    private var menuT = 0f
    private var shakeT = 0f
    private var flashT = 0f
    private var scorePop = 0f
    private var gameOverT = 0f
    private var newBest = false

    // --- run bookkeeping ---
    private var submitted = false

    // --- typing mechanic ---
    private var requiredChar = 'a'
    private var keyFlashT = 0f       // 1 -> 0 feedback animation on the key badge
    private var keyFlashOk = false   // true = correct key (green), false = wrong (red)
    private var keysOk = 0
    private var keysWrong = 0

    // --- leaderboard state ---
    private var lbRows: List<LeaderboardService.Entry> = emptyList()
    private var lbLoading = false
    private var lbError: String? = null

    private val clouds = MutableList(6) {
        Cloud(
            x = (Math.random() * W).toFloat(),
            y = (24 + Math.random() * 280).toFloat(),
            scale = 0.5f + Math.random().toFloat() * 0.9f,
            speedFactor = 0.15f + Math.random().toFloat() * 0.25f,
        )
    }

    private val timer = Timer(16) { tick() }

    init {
        isOpaque = true
        isFocusable = true
        preferredSize = Dimension(420, 640)
        background = C.BG
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        best = PropertiesComponent.getInstance().getInt(BEST_KEY, 0)

        addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_SPACE, KeyEvent.VK_UP -> onPrimary()
                        KeyEvent.VK_L -> {
                            if (state == State.LEADERBOARD) state = State.MENU
                            else if (state == State.MENU || state == State.GAME_OVER || state == State.PAUSED) openLeaderboard()
                        }
                        KeyEvent.VK_O -> {
                            if (state == State.MENU || state == State.GAME_OVER) openSettings()
                        }
                        KeyEvent.VK_ESCAPE -> {
                            if (state == State.LEADERBOARD || state == State.PAUSED) state = State.MENU
                        }
                    }
                }

                override fun keyTyped(e: KeyEvent) {
                    onCharTyped(e.keyChar)
                }
            }
        )

        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    onPrimary()
                }
            }
        )

        // Auto-pause when the user goes back to writing code.
        addFocusListener(
            object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    if (state == State.PAUSED) state = State.PLAYING
                }

                override fun focusLost(e: FocusEvent) {
                    if (state == State.PLAYING) state = State.PAUSED
                }
            }
        )
    }

    // ------------------------------------------------------------------ loop

    override fun addNotify() {
        super.addNotify()
        timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = ((now - (lastTime.takeIf { it != 0L } ?: now)) / 1e9f).coerceIn(0f, 0.05f)
        lastTime = now
        update(dt)
        repaint()
    }

    private var lastTime = 0L

    private fun update(dt: Float) {
        menuT += dt
        if (shakeT > 0) shakeT = (shakeT - dt).coerceAtLeast(0f)
        if (flashT > 0) flashT = (flashT - dt).coerceAtLeast(0f)
        if (scorePop > 0) scorePop = (scorePop - dt * 2.5f).coerceAtLeast(0f)
        if (wingT > 0) wingT = (wingT - dt * 4f).coerceAtLeast(0f)
        if (keyFlashT > 0) keyFlashT = (keyFlashT - dt * 3f).coerceAtLeast(0f)

        for (c in clouds) {
            c.x -= (if (state == State.PLAYING) speed else 40f) * c.speedFactor * dt
            if (c.x < -100) c.x = W + 30 + Math.random().toFloat() * 90
        }

        when (state) {
            State.PLAYING -> physics(dt, dying = false)
            State.GAME_OVER -> {
                gameOverT += dt
                physics(dt, dying = true)
            }
            else -> Unit
        }
    }

    // --------------------------------------------------------------- physics

    private fun physics(dt: Float, dying: Boolean) {
        birdVy = (birdVy + GRAVITY * dt).coerceAtMost(if (dying) 900f else 700f)
        birdY += birdVy * dt

        if (dying) {
            birdRot = (birdRot + dt * 6f).coerceAtMost(PI.toFloat() / 2f)
            if (birdY + BIRD_R > H - GROUND_H) {
                birdY = H - GROUND_H - BIRD_R
                birdVy = 0f
            }
            return
        }

        speed = (BASE_SPEED + score * 2.2f).coerceAtMost(MAX_SPEED)
        groundScroll = (groundScroll + speed * dt) % 40f

        if (pipes.isEmpty() || pipes.last().x < W - SPAWN_SPACING) spawnPipe()

        val iterator = pipes.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.x -= speed * dt
            if (!p.passed && p.x + PIPE_W < BIRD_X - BIRD_R) {
                p.passed = true
                score++
                scorePop = 1f
            }
            if (p.x + PIPE_W < -12) iterator.remove()
        }

        if (birdY - BIRD_R < 0) { // ceiling: clamp, don't kill
            birdY = BIRD_R
            birdVy = 0f
        }
        if (birdY + BIRD_R >= H - GROUND_H) {
            birdY = H - GROUND_H - BIRD_R
            die()
            return
        }
        for (p in pipes) {
            if (circleHits(BIRD_X, birdY, BIRD_R - 1f, p)) {
                die()
                return
            }
        }

        birdRot = (birdVy / 650f).coerceIn(-0.45f, 1.35f)
    }

    private fun spawnPipe() {
        val gapH = (150f - score * 1.1f).coerceAtLeast(112f)
        val margin = 56f
        val lo = margin + gapH / 2
        val hi = H - GROUND_H - margin - gapH / 2
        val gapY = lo + Math.random().toFloat() * (hi - lo)
        pipes.add(Pipe(W + 16f, gapY, gapH))
    }

    private fun circleHits(bx: Float, by: Float, r: Float, p: Pipe): Boolean {
        val topH = p.gapY - p.gapH / 2
        val botY = p.gapY + p.gapH / 2
        return hitsRect(bx, by, r, p.x, 0f, PIPE_W, topH) ||
            hitsRect(bx, by, r, p.x, botY, PIPE_W, H - botY)
    }

    private fun hitsRect(bx: Float, by: Float, r: Float, x: Float, y: Float, w: Float, h: Float): Boolean {
        val cx = bx.coerceIn(x, x + w)
        val cy = by.coerceIn(y, y + h)
        val dx = bx - cx
        val dy = by - cy
        return dx * dx + dy * dy <= r * r
    }

    // ---------------------------------------------------------------- input

    private fun onPrimary() {
        when (state) {
            State.MENU -> startGame()
            State.PLAYING -> Unit // in-game flaps happen only via the shown key
            State.PAUSED -> state = State.PLAYING
            State.GAME_OVER -> if (gameOverT > 0.35f) startGame()
            State.LEADERBOARD -> state = State.MENU
        }
    }

    private fun flap() {
        birdVy = FLAP_VY
        wingT = 1f
    }

    /**
     * Monkeytype-style input: flapping requires typing the shown key.
     * Correct key -> flap + a new key appears. Wrong key -> red flash and
     * a small downward penalty (so brute-forcing every key is a bad strategy).
     */
    private fun onCharTyped(c: Char) {
        if (state != State.PLAYING) return
        if (c.lowercaseChar() == requiredChar.lowercaseChar()) {
            keysOk++
            flap()
            keyFlashOk = true
            keyFlashT = 1f
            pickNextKey()
        } else {
            keysWrong++
            keyFlashOk = false
            keyFlashT = 1f
            birdVy = (birdVy + WRONG_VY_PENALTY).coerceAtMost(WRONG_VY_CAP)
        }
    }

    private fun pickNextKey() {
        val pool = when {
            score >= DIGITS_UNTIL -> LETTERS + DIGITS + SYMBOLS
            score >= LETTERS_UNTIL -> LETTERS + DIGITS
            else -> LETTERS
        }
        var next: Char
        do {
            next = pool[Random.nextInt(pool.length)]
        } while (next == requiredChar && pool.length > 1)
        requiredChar = next
    }

    private fun startGame() {
        pipes.clear()
        birdY = H * 0.42f
        birdVy = -140f
        birdRot = 0f
        score = 0
        speed = BASE_SPEED
        submitted = false
        newBest = false
        keysOk = 0
        keysWrong = 0
        keyFlashT = 0f
        pickNextKey()
        state = State.PLAYING
        requestFocusInWindow()
    }

    private fun die() {
        state = State.GAME_OVER
        gameOverT = 0f
        shakeT = 0.3f
        flashT = 0.22f
        keyFlashT = 0f
        newBest = score > best
        if (newBest) {
            best = score
            PropertiesComponent.getInstance().setValue(BEST_KEY, best.toString())
        }
        submitScore()
    }

    private fun submitScore() {
        if (submitted || score <= 0) return
        submitted = true

        LocalScores.add(localNick(), score)

        val st = FlappyBoardsSettings.instance.state
        if (!LeaderboardService.isConfigured || !st.publishScores) return
        val nick = st.nickname.trim()
        if (nick.isEmpty()) return
        LeaderboardService.submitScore(nick, score) { }
    }

    private fun localNick(): String =
        FlappyBoardsSettings.instance.state.nickname.trim().ifEmpty { "anon" }

    private fun openLeaderboard() {
        state = State.LEADERBOARD
        lbRows = emptyList()
        lbError = null
        if (LeaderboardService.isConfigured) {
            lbLoading = true
            LeaderboardService.fetchTop(TOP_SCORE_LIMIT) { result ->
                lbLoading = false
                result.onSuccess { lbRows = it }
                    .onFailure { lbError = it.message ?: "network error" }
                repaint()
            }
        } else {
            lbRows = LocalScores.top(TOP_SCORE_LIMIT)
        }
    }

    private fun openSettings() {
        val p = project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(p, FlappyBoardsConfigurable::class.java)
    }

    // ---------------------------------------------------------------- paint

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g2.color = background
        g2.fillRect(0, 0, width, height)

        // Fit the logical world into the panel, centered.
        val s = min(width / W, height / H)
        val ox = (width - W * s) / 2f
        val oy = (height - H * s) / 2f
        val shake = shakeT / 0.3f
        val sx = if (shakeT > 0) (Math.random() - 0.5) * 14f * shake else 0.0
        val sy = if (shakeT > 0) (Math.random() - 0.5) * 14f * shake else 0.0
        g2.translate(ox + sx, oy + sy)
        g2.scale(s.toDouble(), s.toDouble())
        g2.clip(Rectangle(0, 0, W.toInt(), H.toInt()))

        drawClouds(g2)
        drawPipes(g2)
        drawGround(g2)
        drawBird(g2)

        when (state) {
            State.MENU -> drawMenu(g2)
            State.PLAYING -> {
                drawScore(g2)
                drawKeyPrompt(g2)
            }
            State.PAUSED -> {
                drawScore(g2)
                drawKeyPrompt(g2)
                drawPaused(g2)
            }
            State.GAME_OVER -> drawGameOver(g2)
            State.LEADERBOARD -> drawLeaderboard(g2)
        }

        if (flashT > 0) {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (flashT / 0.22f) * 0.35f)
            g2.color = Color.WHITE
            g2.fillRect(0, 0, W.toInt(), H.toInt())
        }

        g2.dispose()
    }

    private fun drawClouds(g2: Graphics2D) {
        g2.color = C.CLOUD
        for (c in clouds) {
            val w = 70f * c.scale
            val h = 24f * c.scale
            g2.fill(Ellipse2D.Float(c.x, c.y, w, h))
            g2.fill(Ellipse2D.Float(c.x + w * 0.45f, c.y - h * 0.35f, w * 0.7f, h * 0.9f))
            g2.fill(Ellipse2D.Float(c.x + w * 0.8f, c.y + h * 0.15f, w * 0.5f, h * 0.7f))
        }
    }

    private fun drawPipes(g2: Graphics2D) {
        for (p in pipes) {
            val topH = p.gapY - p.gapH / 2
            val botY = p.gapY + p.gapH / 2
            g2.color = C.PIPE
            g2.fill(RoundRectangle2D.Float(p.x, -12f, PIPE_W, topH + 12f, 10f, 10f))
            g2.fill(RoundRectangle2D.Float(p.x, botY, PIPE_W, H - botY, 10f, 10f))
            g2.color = C.PIPE_DARK
            g2.fill(RoundRectangle2D.Float(p.x - 4f, topH - 14f, PIPE_W + 8f, 14f, 6f, 6f))
            g2.fill(RoundRectangle2D.Float(p.x - 4f, botY, PIPE_W + 8f, 14f, 6f, 6f))
        }
    }

    private fun drawGround(g2: Graphics2D) {
        val top = (H - GROUND_H).toInt()
        g2.color = C.GROUND
        g2.fillRect(0, top, W.toInt(), GROUND_H.toInt())
        g2.color = C.PIPE_DARK
        var x = -groundScroll
        while (x < W) {
            g2.fillRect(x.toInt(), top + 16, 18, 5)
            x += 40f
        }
        g2.color = C.PIPE
        g2.fillRect(0, top - 3, W.toInt(), 3)
    }

    private fun drawBird(g2: Graphics2D) {
        val bob = if (state == State.MENU || state == State.LEADERBOARD) sin(menuT * 2.4).toFloat() * 5f else 0f
        val y = birdY + bob

        g2.translate(BIRD_X.toDouble(), y.toDouble())
        g2.rotate(birdRot.toDouble())

        g2.color = C.BIRD
        g2.fill(Ellipse2D.Float(-BIRD_R, -BIRD_R, BIRD_R * 2, BIRD_R * 2))
        g2.color = C.BIRD_DARK
        g2.fill(Ellipse2D.Float(-BIRD_R, 2f, BIRD_R * 2, BIRD_R))

        val wy = -wingT * 8f
        g2.fill(Ellipse2D.Float(-BIRD_R - 2f, wy - 2f, 15f, 10f))

        g2.color = Color.WHITE
        g2.fill(Ellipse2D.Float(2f, -9f, 9f, 9f))
        g2.color = Color(30, 31, 34)
        g2.fill(Ellipse2D.Float(4.5f, -7.5f, 4f, 4f))

        g2.color = C.BEAK
        val beak = Path2D.Float().apply {
            moveTo(BIRD_R - 2f, -1f)
            lineTo(BIRD_R + 9f, 2f)
            lineTo(BIRD_R - 2f, 5f)
            closePath()
        }
        g2.fill(beak)

        g2.color = C.BIRD_DARK
        g2.draw(Ellipse2D.Float(-BIRD_R, -BIRD_R, BIRD_R * 2, BIRD_R * 2))

        g2.rotate(-birdRot.toDouble())
        g2.translate(-BIRD_X.toDouble(), -y.toDouble())
    }

    private fun drawScore(g2: Graphics2D) {
        g2.font = mono(40f * (1f + scorePop * 0.35f))
        g2.color = C.TEXT
        val text = score.toString()
        val w = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, (W - w) / 2f, 64f)
    }

    /** The key the player must type to flap, shown in a badge under the score. */
    private fun drawKeyPrompt(g2: Graphics2D) {
        val size = 64f
        val x = (W - size) / 2f
        val y = 84f

        g2.color = C.CARD
        g2.fill(RoundRectangle2D.Float(x, y, size, size, 12f, 12f))

        if (keyFlashT > 0) {
            val a = keyFlashT.coerceIn(0f, 1f) * 0.55f
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a)
            g2.color = if (keyFlashOk) C.OK else C.DANGER
            g2.fill(RoundRectangle2D.Float(x, y, size, size, 12f, 12f))
            g2.composite = AlphaComposite.SrcOver
        }

        g2.font = mono(34f)
        val label = requiredChar.toString()
        val w = g2.fontMetrics.stringWidth(label)
        val tx = x + (size - w) / 2f
        val ty = y + (size + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2f
        g2.color = C.TEXT
        g2.drawString(label, tx, ty)

        g2.font = mono(10f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "PRESS", W / 2f, y - 8f)
    }

    private fun drawMenu(g2: Graphics2D) {
        g2.font = mono(34f)
        g2.color = C.TEXT
        drawCentered(g2, "FLAPPY", W / 2f, 150f)
        g2.color = C.ACCENT
        drawCentered(g2, "BOARDS", W / 2f, 192f)

        g2.font = mono(16f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "BEST  $best", W / 2f, 236f)

        val alpha = (0.55f + 0.45f * sin(menuT * 3.2f).toFloat()).coerceIn(0f, 1f)
        val oldComposite = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        g2.font = mono(18f)
        g2.color = C.TEXT
        drawCentered(g2, "CLICK or SPACE to start", W / 2f, 330f)
        g2.composite = oldComposite

        g2.font = mono(13f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "then type the shown key to flap", W / 2f, 368f)
        drawCentered(g2, "L  leaderboard      O  settings", W / 2f, 396f)

        if (FlappyBoardsSettings.instance.state.nickname.isBlank()) {
            drawCentered(g2, "set your nickname in settings to compete", W / 2f, 430f)
        }
    }

    private fun drawPaused(g2: Graphics2D) {
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f)
        g2.color = Color.BLACK
        g2.fillRect(0, 0, W.toInt(), H.toInt())
        g2.composite = AlphaComposite.SrcOver
        g2.font = mono(26f)
        g2.color = C.TEXT
        drawCentered(g2, "PAUSED", W / 2f, H / 2f - 10f)
        g2.font = mono(14f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "click or SPACE to resume", W / 2f, H / 2f + 24f)
    }

    private fun drawGameOver(g2: Graphics2D) {
        val cardW = 250f
        val cardH = 156f
        val cx = (W - cardW) / 2f
        val cy = 140f

        g2.color = C.CARD
        g2.fill(RoundRectangle2D.Float(cx, cy, cardW, cardH, 16f, 16f))

        g2.font = mono(22f)
        g2.color = C.DANGER
        drawCentered(g2, "GAME OVER", W / 2f, cy + 36f)

        g2.font = mono(16f)
        g2.color = C.TEXT
        drawCentered(g2, "SCORE  $score", W / 2f, cy + 66f)
        drawCentered(g2, "BEST   $best", W / 2f, cy + 90f)

        val totalKeys = keysOk + keysWrong
        val accuracy = if (totalKeys > 0) keysOk * 100 / totalKeys else 100
        g2.color = if (accuracy >= 90) C.OK else C.TEXT_DIM
        drawCentered(g2, "ACC    $accuracy%", W / 2f, cy + 114f)

        if (newBest) {
            val alpha = (0.6f + 0.4f * sin(menuT * 6f).toFloat()).coerceIn(0f, 1f)
            val oldComposite = g2.composite
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g2.color = C.ACCENT
            drawCentered(g2, "NEW BEST!", W / 2f, cy + 140f)
            g2.composite = oldComposite
        }

        g2.font = mono(13f)
        g2.color = C.TEXT_DIM
        drawCentered(g2, "SPACE  retry      L  leaderboard", W / 2f, cy + cardH + 28f)
        drawCentered(g2, "O  settings      ESC  menu", W / 2f, cy + cardH + 50f)

        val st = FlappyBoardsSettings.instance.state
        if (LeaderboardService.isConfigured && st.publishScores && st.nickname.isBlank()) {
            g2.color = C.ACCENT
            drawCentered(g2, "add a nickname (O) to enter the leaderboard", W / 2f, cy + cardH + 80f)
        }
    }

    private fun drawLeaderboard(g2: Graphics2D) {
        g2.font = mono(24f)
        g2.color = C.TEXT
        drawCentered(g2, "LEADERBOARD", W / 2f, 64f)

        when {
            lbLoading -> {
                g2.font = mono(15f)
                g2.color = C.TEXT_DIM
                drawCentered(g2, "loading...", W / 2f, 150f)
            }
            lbRows.isNotEmpty() -> {
                g2.font = mono(15f)
                var y = 112f
                for ((i, e) in lbRows.withIndex()) {
                    g2.color = if (i < 3) C.ACCENT else C.TEXT
                    g2.drawString("${(i + 1).toString().padStart(2, ' ')}. ${e.nickname.take(18)}", 34f, y)
                    val sw = g2.fontMetrics.stringWidth(e.score.toString())
                    g2.drawString(e.score.toString(), W - 34f - sw, y)
                    y += 23f
                }
            }
            else -> {
                val local = LocalScores.top(10)
                if (lbError != null) {
                    g2.font = mono(14f)
                    g2.color = C.DANGER
                    drawCentered(g2, lbError!!.take(42), W / 2f, 150f)
                }
                g2.font = mono(15f)
                if (local.isEmpty()) {
                    g2.color = C.TEXT_DIM
                    drawCentered(g2, if (lbError != null) "local best: $best" else "no scores yet — go play!", W / 2f, 190f)
                } else {
                    var y = if (lbError != null) 195f else 130f
                    for ((i, e) in local.withIndex()) {
                        g2.color = C.TEXT
                        g2.drawString("${i + 1}. ${e.nickname.take(18)}", 34f, y)
                        val sw = g2.fontMetrics.stringWidth(e.score.toString())
                        g2.drawString(e.score.toString(), W - 34f - sw, y)
                        y += 23f
                    }
                }
            }
        }

        g2.font = mono(13f)
        g2.color = C.TEXT_DIM
        if (!LeaderboardService.isConfigured) {
            drawCentered(g2, "local mode — configure Supabase (see README)", W / 2f, H - 64f)
        }
        drawCentered(g2, "ESC — back", W / 2f, H - 40f)
    }

    // ---------------------------------------------------------------- utils

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
