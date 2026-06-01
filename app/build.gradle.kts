import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

/** Values baked into BuildConfig only if sandbox-token.properties exists (gitignored). */
val sandboxEmbedProps = Properties().apply {
    val f = rootProject.file("sandbox-token.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun escapeForBuildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val embedSandboxToken = sandboxEmbedProps.getProperty("SANDBOX_TOKEN", "").trim()
val embedSandboxAccountId = sandboxEmbedProps.getProperty("SANDBOX_ACCOUNT_ID", "").trim()

android {
    namespace = "com.example.moexmvp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.moexmvp"
        minSdk = 24
        targetSdk = 34
        versionCode = 157
        versionName = "1.7.39"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "SANDBOX_TOKEN_EMBED", escapeForBuildConfigString(embedSandboxToken))
        buildConfigField("String", "SANDBOX_ACCOUNT_EMBED", escapeForBuildConfigString(embedSandboxAccountId))
    }

    /**
     * Фиксированная подпись debug APK (keystore в репозитории), чтобы сборки GitHub Actions и локальные
     * `assembleDebug` имели **один и тот же сертификат** — иначе на телефоне нельзя обновить приложение
     * поверх старой версии (приходится удалять). Не использовать этот ключ для prod/release.
     */
    signingConfigs {
        create("moexmvpCiDebug") {
            storeFile = file("keystore/moexmvp-ci-debug.jks")
            storePassword = "moexmvp_ci_store"
            keyAlias = "moexmvp_ci"
            keyPassword = "moexmvp_ci_store"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("moexmvpCiDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material:material:1.6.8")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("com.google.firebase:firebase-messaging:25.0.1")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
