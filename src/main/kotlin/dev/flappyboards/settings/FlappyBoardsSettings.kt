package dev.flappyboards.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "FlappyBoardsSettings", storages = [Storage("flappyboards.xml")])
class FlappyBoardsSettings : PersistentStateComponent<FlappyBoardsSettings.State> {

    class State {
        var nickname: String = ""
        var publishScores: Boolean = true
        var renderMode: String = "terminal"   // "terminal" (ASCII) | "classic" (vector)
        var soundsEnabled: Boolean = true
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        val instance: FlappyBoardsSettings
            get() = service<FlappyBoardsSettings>()
    }
}
