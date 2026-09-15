plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.displayhub.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.displayhub.player"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        val supabaseUrl = providers.gradleProperty("DISPLAYHUB_SUPABASE_URL")
            .orElse("https://meqeluddtwthqmrtbhbr.supabase.co")
            .get()
        val anonKey = providers.gradleProperty("DISPLAYHUB_SUPABASE_ANON_KEY")
            .orElse("")
            .get()
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${anonKey.replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
