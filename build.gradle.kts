/**
 * Standalone build for the microkernel runtime JAR.
 *
 * The runtime is the entry point for OOP plugin child JVMs spawned by
 * BossConsole's host. Its compile-time deps come from BossConsole's
 * IPC contract (boss-ipc, boss-ui-sdk, plugin-api-core, plugin-api-ipc),
 * which are published as release assets on the BossConsole repo
 * (Phase 1 of the migration). At build time we either use sibling
 * repo build outputs (local dev) or download those release assets (CI).
 *
 * The fatJar bundles everything the child JVM needs onto its own classpath
 * — no further deps at spawn time other than the plugin's own JAR.
 *
 * Versioning: the project version is the *runtime* version (bumped per
 * release). It is independent of `IpcVersion.CURRENT`, which lives inside
 * the boss-ipc.jar this module depends on. The runtime's `plugin.json`
 * declares `minIpcVersion` so the host can refuse to launch an
 * incompatible runtime — see `IpcVersion` in boss-ipc and the spawn-time
 * gate in BossConsole's `OutOfProcessPluginSpawnerImpl`.
 */
import java.net.URI
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    // Compose Multiplatform — Compose runtime/foundation/ui must ship inside
    // the fatJar because OOP plugins loaded by this runtime use Compose
    // (PluginUIServiceImpl streams widget trees). The host process for an
    // OOP child JVM is *this* runtime, so it owns the Compose runtime.
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

group = "ai.rever.boss.microkernel.runtime"
version = providers.gradleProperty("runtime.version").orElse("1.0.0").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// ─── Upstream IPC jars ─────────────────────────────────────────────────────
//
// Local dev: sibling BossConsole/build/upstream-artifacts/* (run dev-setup.sh
// once, or `./gradlew assembleUpstreamJars` in BossConsole and the symlink
// pulls them in).
//
// CI: jars downloaded into build/downloaded-deps/ by the `downloadDeps`
// task before compile.
val useLocalDependencies: Boolean = (System.getenv("CI") != "true") &&
    file("../../BossConsole/build/upstream-artifacts/boss-ipc-1.0.0.jar").exists()

val upstreamJarDir: File = if (useLocalDependencies) {
    file("../../BossConsole/build/upstream-artifacts")
} else {
    file("build/downloaded-deps")
}

val ipcVersion: String = providers.gradleProperty("ipc.version").orElse("1.0.0").get()

val upstreamJars = listOf(
    "boss-ipc-$ipcVersion.jar",
    "boss-ui-sdk-$ipcVersion.jar",
    "plugin-api-core-$ipcVersion.jar",
    "plugin-api-ipc-$ipcVersion.jar",
)

dependencies {
    // The four IPC-contract jars from BossConsole. compileOnly so they
    // appear on the compile classpath but we control bundling explicitly
    // in the fatJar task below.
    upstreamJars.forEach { jar ->
        compileOnly(files("$upstreamJarDir/$jar"))
    }

    // Transitive runtime libs. Versions match BossConsole's libs.versions.toml.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    // gRPC + protobuf + netty. boss-ipc declares these as `api` so they
    // need to resolve to the same coordinates here for a self-contained
    // child JVM classpath. We also need them at compile time because the
    // generated stubs in boss-ipc reference them.
    implementation("io.grpc:grpc-netty:1.72.0")
    implementation("io.grpc:grpc-protobuf:1.72.0")
    implementation("io.grpc:grpc-stub:1.72.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.3")
    implementation("com.google.protobuf:protobuf-java:4.31.1")
    implementation("com.google.protobuf:protobuf-kotlin:4.31.1")
    implementation("io.netty:netty-transport-native-unix-common:4.2.6.Final")
    implementation("io.netty:netty-transport-native-kqueue:4.2.6.Final:osx-aarch_64")
    implementation("io.netty:netty-transport-native-kqueue:4.2.6.Final:osx-x86_64")
    implementation("io.netty:netty-transport-native-epoll:4.2.6.Final:linux-aarch_64")
    implementation("io.netty:netty-transport-native-epoll:4.2.6.Final:linux-x86_64")

    // Compose runtime — bundled into fatJar so OOP plugins can use it.
    // Versions match BossConsole.
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Decompose for ComponentContext used by plugin Component classes.
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.decompose:extensions-compose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    implementation("com.arkivanov.essenty:state-keeper:2.5.0")
    implementation("com.arkivanov.essenty:back-handler:2.5.0")
    implementation("com.arkivanov.essenty:instance-keeper:2.5.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// ─── downloadDeps: pull upstream jars from BossConsole release ────────────
tasks.register("downloadDeps") {
    group = "build setup"
    description = "Download upstream IPC jars from BossConsole release assets (CI / fresh-clone use)."
    val out = file("build/downloaded-deps")
    val jars = upstreamJars
    val source = providers.gradleProperty("upstream.source").orElse(
        // Default: latest release on the public BossConsole-Releases repo.
        // Override with -Pupstream.source=https://github.com/.../tag/vX.Y.Z
        "https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest/download"
    )
    outputs.dir(out)
    doLast {
        out.mkdirs()
        val baseUrl = source.get().trimEnd('/')
        jars.forEach { jar ->
            val dest = File(out, jar)
            if (dest.exists() && dest.length() > 0) {
                logger.lifecycle("✓ already present: ${dest.name} (${dest.length() / 1024} KB)")
                return@forEach
            }
            val url = "$baseUrl/$jar"
            logger.lifecycle("↓ $url")
            val conn = URI(url).toURL().openConnection().apply {
                setRequestProperty("User-Agent", "boss-microkernel-runtime-build/1.0")
                connectTimeout = 30_000
                readTimeout = 120_000
            }
            conn.getInputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (!dest.exists() || dest.length() == 0L) {
                throw GradleException("Failed to download $jar from $url")
            }
            logger.lifecycle("  ${dest.length() / 1024} KB")
        }
    }
}

tasks.named("compileKotlin") {
    if (!useLocalDependencies) {
        dependsOn("downloadDeps")
    }
}

// ─── fatJar: bundle runtime classes + upstream + transitive runtime libs ──
tasks.jar {
    manifest {
        attributes["Main-Class"] = "ai.rever.boss.plugin.runtime.PluginProcessMainKt"
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Self-contained runtime JAR for child JVM spawn (matches the legacy in-tree :fatJar output)."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "ai.rever.boss.plugin.runtime.PluginProcessMainKt"
    }

    // Our merged service files first (EXCLUDE ensures these win over deps' SPIs).
    from("src/main/resources")
    // Our compiled module classes.
    with(tasks.jar.get())
    // The upstream IPC jars (compileOnly) — must be bundled or the child JVM has nothing to talk to.
    upstreamJars.forEach { jar ->
        from(zipTree(file("$upstreamJarDir/$jar")))
    }
    // Everything else from the runtime classpath (kotlinx, slf4j, grpc, netty, protobuf).
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

// ─── publishMetadata: print the plugin-store payload (release.yml uses sha256) ──
tasks.register("computeFatJarSha256") {
    group = "publishing"
    description = "Compute SHA-256 of the produced fatJar; prints to stdout."
    dependsOn("fatJar")
    doLast {
        val jar = layout.buildDirectory.file("libs/boss-microkernel-runtime-$version-all.jar")
            .get().asFile
        if (!jar.exists()) throw GradleException("fatJar output not found: $jar")
        val md = MessageDigest.getInstance("SHA-256")
        jar.inputStream().use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        logger.lifecycle("fatJar=${jar.absolutePath}")
        logger.lifecycle("size=${jar.length()}")
        logger.lifecycle("sha256=$hex")
        // Also emit machine-readable form for CI shell `eval`.
        File(layout.buildDirectory.get().asFile, "fatjar-sha256.txt").writeText(hex)
        File(layout.buildDirectory.get().asFile, "fatjar-size.txt").writeText(jar.length().toString())
    }
}
