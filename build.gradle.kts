import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "dev.flappyboards"
version = "0.2.6"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.4")
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.flappyboards"
        name = "FlappyBoards"
        version = project.version.toString()
        vendor {
            name = "FlappyBoards"
        }
        description = """
            Flappy Bird x Monkeytype inside your IDE. Take a break between commits and flap
            through pipes — each flap requires typing the key shown on screen, and a new key
            appears after every pipe you pass. Letters first, then digits, then programming
            symbols. Compete with other developers on the global leaderboard.

            Press Ctrl+Alt+F to open the game. L — leaderboard, O — settings, ESC — menu.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.4")
        }
    }
}
