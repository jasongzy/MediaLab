import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun signingProperty(name: String, localName: String): String? =
    providers.gradleProperty(name).orNull ?: keystoreProperties.getProperty(localName)

val taglibArchive = configurations.create("taglibArchive") {
    isTransitive = false
    isCanBeConsumed = false
}
val taglibApi = tasks.register<Sync>("taglibApi") {
    from({ zipTree(taglibArchive.singleFile) })
    include("classes.jar", "proguard.txt")
    into(layout.buildDirectory.dir("taglib-api"))
}

android {
    namespace = "com.jasongzy.medialab"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "com.jasongzy.medialab"
        minSdk = 31
        targetSdk = 37
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
        testInstrumentationRunner = "com.jasongzy.medialab.RegressionTests"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    signingConfigs {
        create("release") {
            storeFile = signingProperty("RELEASE_STORE_FILE", "storeFile")?.let(::file)
            storePassword = signingProperty("RELEASE_STORE_PASSWORD", "storePassword")
            keyAlias = signingProperty("RELEASE_KEY_ALIAS", "keyAlias")
            keyPassword = signingProperty("RELEASE_KEY_PASSWORD", "keyPassword")
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true; compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    androidResources {
        noCompress += "tflite"
        localeFilters += listOf("en", "zh-rCN")
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.excludes += setOf("**/libQnnDsp*.so", "**/libQnnGpu.so")
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
androidComponents.onVariants { variant ->
    variant.proguardFiles.add(taglibApi.map { layout.buildDirectory.file("taglib-api/proguard.txt").get() })
}
dependencyLocking { lockAllConfigurations() }
configurations.configureEach {
    exclude(group = "com.google.android.play", module = "ai-delivery")
}
dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.qualcomm)
    implementation(libs.qnn.runtime)
    implementation(libs.exifinterface)
    add(taglibArchive.name, libs.taglib)
    implementation(files(taglibApi.map { it.destinationDir.resolve("classes.jar") }).builtBy(taglibApi))
    implementation(libs.media3.exoplayer)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
}
