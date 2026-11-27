import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing inputs, read from the environment only.
class ReleaseSigning(
    val keystore: String?,
    val storePassword: String?,
    val keyAlias: String?,
    keyPasswordEnv: String?,
) {
    // The key password defaults to the keystore password.
    val keyPassword: String? = keyPasswordEnv ?: storePassword

    val missing: List<String> = buildList {
        if (keystore == null) add("CLICKER_KEYSTORE")
        if (storePassword == null) add("CLICKER_KEYSTORE_PASSWORD")
        if (keyAlias == null) add("CLICKER_KEY_ALIAS")
    }

    val isComplete: Boolean get() = missing.isEmpty()
}

fun envOrNull(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val releaseSigning = ReleaseSigning(
    keystore = envOrNull("CLICKER_KEYSTORE"),
    storePassword = envOrNull("CLICKER_KEYSTORE_PASSWORD"),
    keyAlias = envOrNull("CLICKER_KEY_ALIAS"),
    keyPasswordEnv = envOrNull("CLICKER_KEY_PASSWORD"),
)

android {
    namespace = "com.janeadora.clicker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.janeadora.clicker"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Release signing comes only from the environment, never from a file in the
    // repo. See docs/android/RELEASE.md. When the variables are missing, the
    // signing config is left off and checkReleaseSigning fails the release build.
    signingConfigs {
        if (releaseSigning.isComplete) {
            create("release") {
                storeFile = file(releaseSigning.keystore!!)
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.jna) { artifact { type = "aar" } }

    // @Preview functions live in the debug source set only.
    debugImplementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // Real BitmapFactory and ExifInterface for the shortcut image tests.
    testImplementation(libs.robolectric)
}

// Rust build. Cargo runs from the repo root (the parent of android/), so this
// works from a clean checkout with no paths outside the repo.

abstract class CargoTask : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSources: ConfigurableFileCollection

    protected fun cargo(vararg args: String) {
        execOps.exec {
            workingDir = repoRoot.get().asFile
            commandLine("cargo", *args)
        }
    }

    // The cargo target dir, honouring CARGO_TARGET_DIR the same way cargo does.
    protected fun targetDir(): File {
        val env = System.getenv("CARGO_TARGET_DIR")
        return if (env.isNullOrBlank()) repoRoot.get().dir("target").asFile else File(env)
    }
}

abstract class CargoNdkTask : CargoTask() {
    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    @TaskAction
    fun build() {
        val ndk = System.getenv("ANDROID_NDK_HOME")
        if (ndk.isNullOrBlank() || !File(ndk).isDirectory) {
            throw GradleException(
                "ANDROID_NDK_HOME is not set or does not exist. Run `source android/env.sh` " +
                    "from the repo root first (see docs/android/SETUP.md).",
            )
        }
        val probe = execOps.exec {
            workingDir = repoRoot.get().asFile
            commandLine("cargo", "ndk", "--version")
            isIgnoreExitValue = true
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
        if (probe.exitValue != 0) {
            throw GradleException(
                "cargo-ndk is not installed. Install it with `cargo install cargo-ndk` " +
                    "(see docs/android/SETUP.md).",
            )
        }
        cargo(
            "ndk", "-t", "arm64-v8a", "-t", "x86_64",
            "-o", jniLibsDir.get().asFile.absolutePath,
            "build", "--release", "-p", "clicker-ffi",
        )
    }
}

abstract class UniffiBindingsTask : CargoTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        cargo("build", "-p", "clicker-ffi")
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        cargo(
            "run", "-p", "clicker-ffi", "--features", "cli", "--bin", "uniffi-bindgen", "--",
            "generate",
            "--library", File(targetDir(), "debug/libclicker_ffi.so").absolutePath,
            "--language", "kotlin",
            "--no-format",
            "--out-dir", out.absolutePath,
        )
    }
}

val repoRootDir: File = rootProject.projectDir.parentFile

fun CargoTask.rustInputs() {
    repoRoot.set(repoRootDir)
    rustSources.from(
        File(repoRootDir, "Cargo.toml"),
        File(repoRootDir, "Cargo.lock"),
        fileTree(File(repoRootDir, "crates")) { exclude("**/target/**") },
    )
}

val cargoNdk = tasks.register<CargoNdkTask>("cargoNdk") {
    group = "rust"
    description = "Builds libclicker_ffi.so for arm64-v8a and x86_64 into src/main/jniLibs."
    rustInputs()
    jniLibsDir.set(layout.projectDirectory.dir("src/main/jniLibs"))
}

val uniffiBindings = tasks.register<UniffiBindingsTask>("uniffiBindings") {
    group = "rust"
    description = "Generates the Kotlin bindings for clicker-ffi into build/generated/uniffi."
    rustInputs()
    outputDir.set(layout.buildDirectory.dir("generated/uniffi"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindings, UniffiBindingsTask::outputDir)
    }
}

tasks.named("preBuild") {
    dependsOn(cargoNdk, uniffiBindings)
}

// Fails a release build up front, naming every missing signing variable, rather
// than producing an unsigned APK or falling back to the debug key. Debug builds
// never run it.
val missingSigningVars: List<String> = releaseSigning.missing
val keystorePath: String? = releaseSigning.keystore
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Checks that the release signing environment variables are set."
    doLast {
        if (missingSigningVars.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing environment variable(s): " +
                    missingSigningVars.joinToString(", ") + ". Set CLICKER_KEYSTORE, " +
                    "CLICKER_KEYSTORE_PASSWORD and CLICKER_KEY_ALIAS (CLICKER_KEY_PASSWORD " +
                    "defaults to the keystore password). See docs/android/RELEASE.md.",
            )
        }
        val ks = File(keystorePath!!)
        if (!ks.isFile) {
            throw GradleException(
                "CLICKER_KEYSTORE does not point to a file. See docs/android/RELEASE.md.",
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkReleaseSigning)
}

// In a release build the check goes first, so a missing key stops the build
// before the Rust library is compiled.
cargoNdk.configure { mustRunAfter(checkReleaseSigning) }
uniffiBindings.configure { mustRunAfter(checkReleaseSigning) }
