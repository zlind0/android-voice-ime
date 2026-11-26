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

// Ensure handwriting NCNN assets (Ismantic/Handwritten, Apache-2.0) exist if building from a fresh clone.
fun ensureAssetFile(target: File, urls: List<String>) {
    if (target.isFile && target.length() > 0L) return
    target.parentFile.mkdirs()
    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    var lastError: Exception? = null
    for (url in urls) {
        try {
            val partial = File(target.parentFile, target.name + ".part")
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build()
            val response = client.send(
                request,
                HttpResponse.BodyHandlers.ofFile(
                    partial.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ),
            )
            if (response.statusCode() in 200..299 && partial.length() > 0L) {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            }
            partial.delete()
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw GradleException(
        "Failed to download asset ${target.name} from $urls. Last error: ${lastError?.message}",
        lastError,
    )
}

val handwritingAssetsDir = layout.projectDirectory.dir("src/main/assets/handwriting").asFile
listOf("model.ncnn.param", "model.ncnn.bin", "charset.json").forEach { name ->
    ensureAssetFile(
        target = File(handwritingAssetsDir, name),
        urls = listOf(
            "https://huggingface.co/Ismantic/Handwritten/resolve/main/ncnn/$name",
            "https://hf-mirror.com/Ismantic/Handwritten/resolve/main/ncnn/$name",
        ),
    )
}
ensureAssetFile(
    target = layout.projectDirectory.file("src/main/assets/emoji/root.txt").asFile,
    urls = listOf(
        "https://raw.githubusercontent.com/florisboard/florisboard/main/app/src/main/assets/ime/media/emoji/root.txt",
    ),
)

android {
    namespace = "com.litertime.android"
    compileSdk = 37
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.litertime.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only the ABIs that can actually run the bundled runtimes are packaged.
        // sherpa-onnx's AAR ships armeabi-v7a/x86 too, but litertlm only ships
        // arm64-v8a and x86_64, so 32-bit builds would install a keyboard whose
        // AI features can never load. The `splits` block below turns each ABI
        // into its own APK, so the arm64 package never carries x86_64 libraries.
        // (AGP 9 rejects ndk.abiFilters together with splits.abi.include.)
        externalNativeBuild {
            cmake {
                arguments("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    // One APK per ABI so an arm64 install never carries x86_64 libraries.
    // `isUniversalApk = false` keeps a single fat APK from being produced.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        // Debug is also shrunk: material-icons-extended contributes thousands of
        // unused icon classes to the dex, and R8 removes them automatically.
        // Obfuscation stays off (proguard-debug.pro) so stack traces stay readable.
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-debug.pro",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Signed with the local debug key so `assembleRelease` can be
            // sideloaded directly; replace with a real keystore for distribution.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
            pickFirsts.add("**/libomp.so")
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
    implementation(libs.litertlm) {
        // Pulled in by litertlm's ReflectionTool (function calling), which this
        // app never uses. R8 keeps the litertlm classes we need; excluding the
        // artifact here removes ~3 MB of reflection support from the dex.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Kept as a normal dependency: R8 keeps only the icons that are actually
    // referenced, so adding a new icon later is just an import with no manual
    // "copy the vector out of the library" step. This works for debug too
    // because debug builds are minified (see buildTypes above).
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
