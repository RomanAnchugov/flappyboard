package dev.flappyboards

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import dev.flappyboards.game.GamePanel

class FlappyBoardsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val gamePanel = GamePanel(project)
        val content = toolWindow.contentManager.factory.createContent(gamePanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
