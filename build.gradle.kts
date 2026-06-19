import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("org.jetbrains.changelog") version "2.2.1"
}

group = "io.github.mmhelloworld"
version = "0.1.2"

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
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Use the platform interfaces' Java default methods directly instead
        // of generating DefaultImpls bridges in implementing classes — the
        // bridges count as overrides of @ApiStatus.Internal members
        // (ToolWindowFactory.getAnchor/getIcon/manage) and fail verifyPlugin.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.mmhelloworld.idrisintellij"
        name = "Idris 2 (JVM)"
        version = project.version.toString()
        // Marketplace "What's New" = the current version's CHANGELOG.md section,
        // rendered to HTML into plugin.xml <change-notes> (without the version
        // header, which the Marketplace shows separately). Falls back to the
        // [Unreleased] section if the version has no entry yet.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    // Marketplace plugin signing: `./gradlew signPlugin` (and publishPlugin
    // signs implicitly). Key generation is documented in the README.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// Build against the oldest supported platform (2024.2 / sinceBuild 242) but
// offer a sandbox on the newest IDE: `./gradlew runIdeLatest`. The old
// sandbox's bundled Gradle plugin chokes on Java 25 in its compatibility
// matrix; 2026.x parses it fine, so no disabled_plugins workaround there.
intellijPlatformTesting {
    runIde {
        register("runIdeLatest") {
            // IDEA Community is no longer published since 2025.3; the unified
            // IntelliJ IDEA distribution (free mode) replaces it.
            type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea
            version = "2026.1.3"
            // Patch releases have no standalone installer; resolve from the
            // intellij-repository Maven artifacts instead.
            useInstaller = false
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
