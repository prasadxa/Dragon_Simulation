// Plugin versions — bump these if a version fails to resolve at build time.
// SceneView 4.39.0 + Compose BOM 2026.09 require compileSdk 37 / AGP >= 9.1,
// and AGP 9.x requires Gradle 9.6+ (wrapper pins Gradle 9.8.0).
plugins {
    // AGP 9.x has built-in Kotlin support — the org.jetbrains.kotlin.android
    // plugin is rejected. The Compose compiler plugin still applies on top.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
