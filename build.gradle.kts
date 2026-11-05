// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so only the Compose compiler plugin is added.
// If Android Studio offers to upgrade these versions, accept.
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
