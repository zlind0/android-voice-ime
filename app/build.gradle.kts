import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Prebuilt sherpa-onnx AAR
//
// The AAR is ~50 MB, so it is not committed to git (see app/libs/*.aar in
// .gitignore). Instead it is fetched once from the upstream GitHub release and
// cached in app/libs/. The download happens while the build is configured so
// that IDE sync and the first compile both see the file.
//
// Bump the version with -PsherpaOnnxVersion=x.y.z (and pass the matching
// -PsherpaOnnxSha256=... when the upstream checksum changes).
// ---------------------------------------------------------------------------
val sherpaOnnxVersion = providers.gradleProperty("sherpaOnnxVersion").getOrElse("2.0.2")
val sherpaOnnxSha256 = providers.gradleProperty("sherpaOnnxSha256")
    .getOrElse("6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e")
val sherpaOnnxAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar").asFile

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun ensurePrebuiltAar(target: File, url: String, expectedSha256: String) {
    if (target.isFile) return

    target.parentFile.mkdirs()
    val partial = File(target.parentFile, target.name + ".part")
    logger.lifecycle("sherpa-onnx: downloading $url")

    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(10))
        .GET()
        .build()

    val response = try {
        client.send(
            request,
            HttpResponse.BodyHandlers.ofFile(
                partial.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ),
        )
    } catch (e: Exception) {
        throw GradleException(
            "Failed to download the sherpa-onnx AAR from $url.\n" +
                "Download it manually and place it at ${target.absolutePath}.\n" +
                "Cause: ${e.message}",
            e,
        )
    }

    if (response.statusCode() !in 200..299) {
        throw GradleException(
            "Failed to download the sherpa-onnx AAR from $url: HTTP ${response.statusCode()}.\n" +
                "Download it manually and place it at ${target.absolutePath}.",
        )
    }

    val actualSha256 = sha256Of(partial)
    if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
        throw GradleException(
            "Checksum mismatch for the sherpa-onnx AAR downloaded from $url.\n" +
                "Expected: $expectedSha256\n" +
                "Actual:   $actualSha256\n" +
                "The partial download is left at ${partial.absolutePath}; delete it or fix " +
                "-PsherpaOnnxVersion / -PsherpaOnnxSha256 and rebuild.",
        )
    }

    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    logger.lifecycle("sherpa-onnx: saved ${target.absolutePath}")
}

ensurePrebuiltAar(
    target = sherpaOnnxAar,
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/" +
        "sherpa-onnx-$sherpaOnnxVersion.aar",
    expectedSha256 = sherpaOnnxSha256,
)

android {
    namespace = "com.litertime.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.litertime.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            pickFirsts.add("**/liblitertlm_jni.so")
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(files(sherpaOnnxAar))
    implementation(libs.litertlm)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
