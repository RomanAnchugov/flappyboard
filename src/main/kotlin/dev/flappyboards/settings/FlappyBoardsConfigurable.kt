package dev.flappyboards.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import dev.flappyboards.leaderboard.LeaderboardService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField

/** Settings → Tools → FlappyBoards: nickname, score publishing, graphics mode. */
class FlappyBoardsConfigurable : Configurable, Configurable.NoScroll {

    private val nicknameField = JTextField(24)
    private val publishBox = JCheckBox("Publish my scores to the global leaderboard")
    private val terminalRadio = JRadioButton("Terminal — ASCII (stealth mode)")
    private val classicRadio = JRadioButton("Classic — flat vector graphics")

    init {
        ButtonGroup().apply {
            add(terminalRadio)
            add(classicRadio)
        }
    }

    private val state get() = FlappyBoardsSettings.instance.state

    override fun getDisplayName(): String = "FlappyBoards"

    override fun createComponent(): JComponent {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

            add(JLabel("Nickname shown on the leaderboard (max 24 chars):").apply { alignmentX = 0f })
            add(Box.createVerticalStrut(4))
            add(nicknameField.apply {
                alignmentX = 0f
                maximumSize = Dimension(240, preferredSize.height)
            })
            add(Box.createVerticalStrut(10))
            add(publishBox.apply { alignmentX = 0f })
            add(Box.createVerticalStrut(10))
            add(JLabel("Graphics:").apply { alignmentX = 0f })
            add(Box.createVerticalStrut(4))
            add(terminalRadio.apply { alignmentX = 0f })
            add(classicRadio.apply { alignmentX = 0f })
            add(Box.createVerticalStrut(6))
            add(
                JLabel(
                    if (LeaderboardService.isConfigured) {
                        "<html>Connected to the global leaderboard (Supabase).</html>"
                    } else {
                        "<html><body style=\"width:360px\">Global leaderboard is not configured — scores are kept " +
                            "locally. See the project README to plug in your own Supabase instance.</body></html>"
                    }
                ).apply {
                    alignmentX = 0f
                    foreground = JBColor.GRAY
                }
            )
        }
        reset()
        return JPanel(BorderLayout()).apply { add(content, BorderLayout.NORTH) }
    }

    private val selectedRenderMode: String
        get() = if (classicRadio.isSelected) "classic" else "terminal"

    override fun isModified(): Boolean =
        nicknameField.text.trim() != state.nickname ||
            publishBox.isSelected != state.publishScores ||
            selectedRenderMode != state.renderMode

    override fun apply() {
        state.nickname = nicknameField.text.trim().take(24)
        state.publishScores = publishBox.isSelected
        state.renderMode = selectedRenderMode
    }

    override fun reset() {
        nicknameField.text = state.nickname
        publishBox.isSelected = state.publishScores
        classicRadio.isSelected = state.renderMode == "classic"
        terminalRadio.isSelected = state.renderMode != "classic"
    }
}
