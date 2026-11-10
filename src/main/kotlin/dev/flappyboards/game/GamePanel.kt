package dev.flappyboards.game

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import dev.flappyboards.leaderboard.LeaderboardService
import dev.flappyboards.leaderboard.LocalScores
import dev.flappyboards.settings.FlappyBoardsConfigurable
import dev.flappyboards.settings.FlappyBoardsSettings
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole game: physics + input + state, with rendering delegated to a
 * pluggable [GameRenderer] (vector "classic" or terminal "ASCII" mode).
 * The logical world is 360x560 units; renderers scale it as they like.
 */
class GamePanel(private val project: Project? = null) : JPanel() {

    private companion object {
        const val BEST_KEY = "dev.flappyboards.best"

        // Logical world size (game units, not pixels).
        const val W = 360f
        const val H = 560f
        const val GROUND_H = 44f

        // Physics (units per second) — deliberately gentle: the bird floats.
        const val GRAVITY = 800f
        const val FLAP_VY = -330f
        const val BASE_SPEED = 132f
        const val MAX_SPEED = 220f

        // Bird.
        const val BIRD_X = 92f
        const val BIRD_R = 13f

        // Pipes.
        const val PIPE_W = 62f
        const val SPAWN_SPACING = 300f

        // Collision forgiveness (classic game-feel trick): hitboxes are a bit
        // smaller than the visuals, so grazes don't kill. Bird radius is
        // 13 units on screen, pipes look 62 wide — hitboxes below.
        const val BIRD_HITBOX_R = 9f
        const val PIPE_HITBOX_INSET = 6f

        const val TOP_SCORE_LIMIT = 20

        // Typing mechanic: the key pool grows with the score.
        const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
        const val DIGITS = "0123456789"
        const val SYMBOLS = "!@#$%^&*()-_=+[]{};:'\",.<>/?\\|`~"
        const val LETTERS_UNTIL = 10   // letters only below this score
        const val DIGITS_UNTIL = 20    // + digits below this score, then all symbols

        // Light anti-spam: each wrong keypress pushes the bird down a bit.
        const val WRONG_VY_PENALTY = 60f
        const val WRONG_VY_CAP = 420f
    }

    private class Pipe(var x: Float, val gapY: Float, val gapH: Float, var passed: Boolean = false)

    // --- game state ---
    private var state = GameState.MENU
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
        CloudData(
            x = (Math.random() * W).toFloat(),
            y = (24 + Math.random() * 280).toFloat(),
            scale = 0.5f + Math.random().toFloat() * 0.9f,
            speedFactor = 0.15f + Math.random().toFloat() * 0.25f,
        )
    }

    private val timer = Timer(16) { tick() }

    private val vectorRenderer = VectorRenderer()
    private val asciiRenderer = AsciiRenderer()

    init {
        isOpaque = true
        isFocusable = true
        preferredSize = Dimension(420, 640)
        background = UIUtil.getPanelBackground()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        best = PropertiesComponent.getInstance().getInt(BEST_KEY, 0)
        pickNextKey() // the first key of the next run, shown on the menu

        addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    // Service keys act only on service screens (menu / game over /
                    // leaderboard). They must never fire during READY/PLAYING/PAUSED:
                    // 'l' and 'o' are typing keys there, and a paused game (boss
                    // screen) should stay a build log no matter what is pressed.
                    when (e.keyCode) {
                        KeyEvent.VK_SPACE, KeyEvent.VK_UP -> onPrimary()
                        KeyEvent.VK_L -> {
                            when (state) {
                                GameState.LEADERBOARD -> state = GameState.MENU
                                GameState.MENU, GameState.GAME_OVER -> openLeaderboard()
                                else -> Unit
                            }
                        }
                        KeyEvent.VK_O -> {
                            if (state == GameState.MENU || state == GameState.GAME_OVER) openSettings()
                        }
                        KeyEvent.VK_ESCAPE -> {
                            when (state) {
                                GameState.LEADERBOARD, GameState.READY -> state = GameState.MENU
                                GameState.PAUSED -> state = GameState.MENU // abandon the run
                                else -> Unit
                            }
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
                    if (state == GameState.PAUSED) state = GameState.PLAYING
                }

                override fun focusLost(e: FocusEvent) {
                    if (state == GameState.PLAYING) state = GameState.PAUSED
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
            c.x -= (if (state == GameState.PLAYING) speed else 40f) * c.speedFactor * dt
            if (c.x < -100) c.x = W + 30 + Math.random().toFloat() * 90
        }

        when (state) {
            GameState.PLAYING -> physics(dt, dying = false)
            GameState.GAME_OVER -> {
                gameOverT += dt
                physics(dt, dying = true)
            }
            GameState.READY -> {
                // Bird hovers: no gravity, gentle bobbing while the player
                // finds the shown key on screen.
                birdY = H * 0.5f + sin(menuT * 2.2f) * 12f
                birdRot = 0f
            }
            else -> Unit
        }
    }

    // --------------------------------------------------------------- physics

    private fun physics(dt: Float, dying: Boolean) {
        birdVy = (birdVy + GRAVITY * dt).coerceAtMost(if (dying) 620f else 480f)
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
                GameSounds.ding()
                pickNextKey() // a new key appears after every passed pipe
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
            if (circleHits(BIRD_X, birdY, BIRD_HITBOX_R, p)) {
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

    /** Pipe hitbox is inset from the visible pipe on every side. */
    private fun circleHits(bx: Float, by: Float, r: Float, p: Pipe): Boolean {
        val inset = PIPE_HITBOX_INSET
        val x = p.x + inset
        val w = PIPE_W - inset * 2
        val topH = p.gapY - p.gapH / 2
        val botY = p.gapY + p.gapH / 2
        return hitsRect(bx, by, r, x, -50f, w, topH + 50f - inset) ||
            hitsRect(bx, by, r, x, botY + inset, w, H - botY - inset)
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
            GameState.MENU -> startGame()
            GameState.PLAYING -> Unit // in-game flaps happen only via the shown key
            GameState.READY -> Unit   // waiting for the shown key
            GameState.PAUSED -> state = GameState.PLAYING
            GameState.GAME_OVER -> if (gameOverT > 0.35f) startGame()
            GameState.LEADERBOARD -> state = GameState.MENU
        }
    }

    private fun flap() {
        birdVy = FLAP_VY
        wingT = 1f
        GameSounds.flap()
    }

    /**
     * Monkeytype-style input: flapping requires typing the shown key.
     * Correct key -> flap + a new key appears. Wrong key -> red flash and
     * a small downward penalty (so brute-forcing every key is a bad strategy).
     */
    private fun onCharTyped(c: Char) {
        if (state != GameState.PLAYING && state != GameState.READY) return
        if (c.lowercaseChar() == requiredChar.lowercaseChar()) {
            keysOk++
            flap()
            keyFlashOk = true
            keyFlashT = 1f
            if (state == GameState.READY) {
                // First correct press starts the run.
                state = GameState.PLAYING
            }
        } else {
            keysWrong++
            keyFlashOk = false
            keyFlashT = 1f
            GameSounds.buzz()
            if (state == GameState.PLAYING) {
                // Light anti-spam: wrong keypresses push the bird down a bit.
                birdVy = (birdVy + WRONG_VY_PENALTY).coerceAtMost(WRONG_VY_CAP)
            }
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
        birdY = H * 0.5f
        birdVy = 0f
        birdRot = 0f
        score = 0
        speed = BASE_SPEED
        submitted = false
        newBest = false
        keysOk = 0
        keysWrong = 0
        keyFlashT = 0f
        // The key was already picked (shown on menu / game-over screen);
        // the run itself starts on the first correct press (READY -> PLAYING).
        state = GameState.READY
        requestFocusInWindow()
    }

    private fun die() {
        state = GameState.GAME_OVER
        gameOverT = 0f
        shakeT = 0.3f
        flashT = 0.22f
        keyFlashT = 0f
        newBest = score > best
        if (newBest) {
            best = score
            PropertiesComponent.getInstance().setValue(BEST_KEY, best.toString())
        }
        pickNextKey() // pre-pick the next run's key so the game-over screen can show it
        GameSounds.death()
        if (newBest) GameSounds.best()
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
        state = GameState.LEADERBOARD
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

    private fun renderer(): GameRenderer =
        if (FlappyBoardsSettings.instance.state.renderMode == "classic") vectorRenderer else asciiRenderer

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        renderer().paint(g2, snapshot(), width, height)
        g2.dispose()
    }

    private fun snapshot(): GameSnapshot {
        val st = FlappyBoardsSettings.instance.state
        return GameSnapshot(
            state = state,
            worldW = W,
            worldH = H,
            birdX = BIRD_X,
            birdR = BIRD_R,
            pipeW = PIPE_W,
            groundH = GROUND_H,
            birdY = birdY,
            birdRot = birdRot,
            wingT = wingT,
            pipes = pipes.map { PipeData(it.x, it.gapY, it.gapH) },
            clouds = clouds,
            groundScroll = groundScroll,
            score = score,
            best = best,
            requiredChar = requiredChar,
            keyFlashT = keyFlashT,
            keyFlashOk = keyFlashOk,
            keysOk = keysOk,
            keysWrong = keysWrong,
            menuT = menuT,
            shakeT = shakeT,
            flashT = flashT,
            scorePop = scorePop,
            newBest = newBest,
            lbRows = lbRows,
            lbLoading = lbLoading,
            lbError = lbError,
            nicknameBlank = st.nickname.isBlank(),
            publishScores = st.publishScores,
        )
    }
}
