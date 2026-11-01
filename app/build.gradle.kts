plugins {
    alias(libs.plugins.android.application)
}

// The version is derived from git so the tag, the APK and the version shown inside the app can
// never disagree (v1.1 shipped with versionCode 1 / versionName 1.0, which made updaters such as
// Obtainium offer the same release forever).
//
//   versionName  = the last tag, e.g. `git tag v1.2` -> "1.2"
//   versionCode  = the number of commits, which only ever grows
//
// Cut the tag before publishing a release. Without git (source tarball) the build still succeeds.
fun git(vararg args: String): String = try {
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    ""
}

val gitTag: String = git("describe", "--tags", "--abbrev=0").removePrefix("v").ifEmpty { "1.2" }
val gitCommitCount: Int = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1

android {
    namespace = "com.rickmschulz.dnsautoswitcher"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.rickmschulz.dnsautoswitcher"
        minSdk = 28
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = gitTag

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // No reflection anywhere in the app, so the default rules are enough. This takes the
            // release APK from ~10 MB to ~2.3 MB and shortens cold start.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
