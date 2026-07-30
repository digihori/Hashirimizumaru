import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val msilSubscriptionKey = localProperties.getProperty("MSIL_SUBSCRIPTION_KEY", "")

android {
    namespace = "tk.horiuchi.hashirimizumaru"
    compileSdk = 35

    defaultConfig {
        applicationId = "tk.horiuchi.hashirimizumaru"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            "\"https://raw.githubusercontent.com/digihori/Hashirimizumaru/main/PRIVACY.ja.md\""
        )
        buildConfigField("String", "MSIL_SUBSCRIPTION_KEY", "\"$msilSubscriptionKey\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/privacyAssets"))
}

val generatePrivacyAsset by tasks.registering(Copy::class) {
    from(rootProject.file("PRIVACY.ja.md"))
    into(layout.buildDirectory.dir("generated/privacyAssets"))
    rename { "privacy_ja.md" }
}

tasks.named("preBuild").configure {
    dependsOn(generatePrivacyAsset)
}

afterEvaluate {
    val appName =
        android.defaultConfig.applicationId
            ?.substringAfterLast(".")
            ?: "app"

    val version =
        android.defaultConfig.versionName
            ?: "0.0.0"

    base {
        archivesName.set("${appName}-${version}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.1")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
