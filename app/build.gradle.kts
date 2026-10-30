plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.devilmusic.androidproof"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.devilmusic.androidproof"
        // An isolated install for destructive queue/profile fixtures; never use user data for QA.
        if (providers.gradleProperty("isolatedQa").orNull == "true") applicationIdSuffix = ".qa"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.0.17-preview"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    val debugStore = providers.environmentVariable("ANDROID_DEBUG_KEYSTORE_FILE").orNull
    if (debugStore != null) signingConfigs.getByName("debug") { storeFile = file(debugStore) }
    val releaseStore = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
    if (releaseStore != null) {
        signingConfigs.create("previewRelease") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("previewRelease")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-session:1.9.4")
    implementation("androidx.javascriptengine:javascriptengine:1.1.1")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.yaml:snakeyaml:2.5")
    testImplementation("junit:junit:4.13.2")
}

