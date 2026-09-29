plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        dependencies {
            add("implementation", "androidx.media3:media3-exoplayer:1.9.4")
            add("implementation", "androidx.media3:media3-ui:1.9.4")
        }
    }
}
