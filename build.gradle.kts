import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "io.github.mmhelloworld"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.4")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.mmhelloworld.idris2"
        name = "Idris 2 (JVM)"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    // Integration tests against a real compiler are enabled by setting IDRIS2_EXEC
    environment("IDRIS2_EXEC", System.getenv("IDRIS2_EXEC") ?: "")
}

tasks.prepareSandbox {
    // The bundled Gradle plugin periodically downloads a Gradle<->JVM
    // compatibility matrix that now lists Java 25, which 2024.2's
    // JavaVersion.parse rejects — a PluginException on every sandbox startup.
    // Gradle support is irrelevant for developing this plugin; keep it off.
    doLast {
        sandboxConfigDirectory.get().asFile.resolve("disabled_plugins.txt").writeText(
            """
            com.intellij.gradle
            org.jetbrains.plugins.gradle
            org.jetbrains.plugins.gradle.maven
            """.trimIndent() + "\n",
        )
    }
}
