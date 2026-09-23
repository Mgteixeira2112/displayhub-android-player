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
        versionCode = 20
        versionName = "0.4.15"

        val supabaseUrl = providers.gradleProperty("DISPLAYHUB_SUPABASE_URL")
            .orElse("https://meqeluddtwthqmrtbhbr.supabase.co")
            .get()
        val configuredKey = providers.gradleProperty("DISPLAYHUB_SUPABASE_ANON_KEY").orNull?.trim()
        val publishableKey = configuredKey?.takeIf { it.isNotEmpty() }
            ?: "sb_publishable_yjnvIPUmi8-Kt7yTFibw3w_DQlawViE"
        val updateManifestUrl = providers.gradleProperty("DISPLAYHUB_UPDATE_MANIFEST_URL")
            .orElse("https://mgteixeira2112.github.io/displayhub-android-player/latest.json")
            .get()
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${publishableKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"${updateManifestUrl.replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
        val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val keyAliasValue = System.getenv("ANDROID_KEY_ALIAS")
        val keyPasswordValue = System.getenv("ANDROID_KEY_PASSWORD")
        if (!keystoreFile.isNullOrBlank() && !keystorePassword.isNullOrBlank() && !keyAliasValue.isNullOrBlank() && !keyPasswordValue.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
        }
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
