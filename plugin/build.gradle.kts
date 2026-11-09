import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val repository = projectDir.parentFile
val pluginVersion = repository.resolve("VERSION").readText().trim()
version = pluginVersion
val apiPin = Properties().apply {
    repository.resolve("plugin-api.lock").inputStream().use { load(it) }
}

android {
    namespace = "com.sleepysoong.logent.customopenai"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "PLUGIN_VERSION", "\"$pluginVersion\"")
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val stagedAssets = tasks.register<Sync>("stagePluginAssets") {
    from(layout.projectDirectory.dir("src/main/assets"))
    into(layout.buildDirectory.dir("staged/pluginAssets"))
}
apply(from = repository.resolve("scripts/artifacts.gradle.kts"))

dependencies {
    implementation("${apiPin.getProperty("group")}:${apiPin.getProperty("artifact")}:${apiPin.getProperty("version")}")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.withType<Test>().configureEach {
    dependsOn(stagedAssets)
    systemProperty(
        "logent.plugin.assets",
        layout.buildDirectory.dir("staged/pluginAssets").get().asFile.absolutePath,
    )
    maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
}
