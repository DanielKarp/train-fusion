import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Where the backend is and the sign-in settings: android/config.properties (gitignored; written
// by bin/android-config, like ios/Config.xcconfig). Without it the app records and replays as
// usual but can't upload or sign in.
val config = Properties().apply {
    rootProject.file("config.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun config(key: String): String = config.getProperty(key)?.trim() ?: ""

// Release signing, if android/keystore.properties (gitignored) says how: README.md, "Release builds".
val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.danielkarp.trainfusion"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.danielkarp.trainfusion"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        // "-android": meta.json and Train Measurements say which app made them
        versionName = "0.1-android"
        buildConfigField("String", "API_HOST", "\"${config("API_HOST")}\"")
        buildConfigField("String", "IDENTITY_API_KEY", "\"${config("IDENTITY_API_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${config("GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("String", "SITE_HOST", "\"${config("SITE_HOST")}\"")
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // without a release key, signed with the debug key so that it installs (for testing only)
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // the screenshot tests render the screens with Robolectric (README.md, "Tests")
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // The model's numbers are copied from ../ios at build time, not translated strings.
        disable += "MissingTranslation"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

/** The fitted model: ../ios/TrainRecorder/model.json, written by `just ios-model`, shared by both apps. */
abstract class CopyModel : DefaultTask() {
    @get:InputFile abstract val source: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.mkdirs()
        source.get().asFile.copyTo(File(out, "model.json"), overwrite = true)
    }
}

val copyModel = tasks.register<CopyModel>("copyModel") {
    source.set(rootProject.layout.projectDirectory.file("../ios/TrainRecorder/model.json"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(copyModel, CopyModel::outputDir) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
