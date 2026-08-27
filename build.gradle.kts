plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.dshui"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 优先用本机 IDEA（gradle.properties 的 localIdeaHome）；未设置时从官方源解析 2026.1
        val localIdeaHome = providers.gradleProperty("localIdeaHome")
        if (localIdeaHome.isPresent) local(localIdeaHome.get()) else create("IU", "2026.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
    implementation("com.github.luben:zstd-jni:1.5.7-4")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.dshui.idea"
        name = "DeepSeek Harness (dsh-tui)"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    // 发布到 JetBrains Marketplace：
    // 1) plugins.jetbrains.com → 头像 → Upload plugin 拿到 token（或网页直接上传 zip）
    // 2) set PUBLISH_TOKEN=xxx && gradlew publishPlugin
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // token 为空时 publishPlugin 会失败，属预期（仅发布时需要）
    }
}

tasks.test {
    useJUnit()
}
