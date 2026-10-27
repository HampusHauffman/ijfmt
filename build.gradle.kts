import java.io.File

plugins {
    base
}

allprojects {
    group = "dev.ijfmt"
    version = "0.1.0"
}

/**
 * The IntelliJ installation the daemon plugin is compiled against (and that the conformance tests use as the oracle).
 * Resolution order: -PideHome=..., $IJFMT_IDE_HOME, then well-known install locations (newest build wins).
 */
fun detectIdeHome(): String? {
    val home = System.getProperty("user.home")
    val candidates = mutableListOf<File>()
    fun addChildren(dir: File, filter: (File) -> Boolean = { true }) {
        dir.listFiles()?.filter { it.isDirectory && filter(it) }?.let(candidates::addAll)
    }
    System.getenv("LOCALAPPDATA")?.let { addChildren(File(it, "Programs")) { f -> f.name.startsWith("IntelliJ IDEA") } }
    System.getenv("ProgramFiles")?.let { addChildren(File(it, "JetBrains")) { f -> f.name.startsWith("IntelliJ IDEA") } }
    addChildren(File("/Applications")) { it.name.startsWith("IntelliJ IDEA") && it.name.endsWith(".app") }
    addChildren(File(home, "Applications")) { it.name.startsWith("IntelliJ IDEA") && it.name.endsWith(".app") }
    addChildren(File("/opt")) { it.name.startsWith("idea") || it.name.startsWith("intellij") }
    listOf(
        File(home, ".local/share/JetBrains/Toolbox/apps"),
        File(home, "Library/Application Support/JetBrains/Toolbox/apps"),
        File(System.getenv("LOCALAPPDATA") ?: "", "JetBrains/Toolbox/apps"),
    ).forEach { addChildren(it) { f -> f.name.startsWith("intellij-idea") || f.name.startsWith("IDEA") } }

    val homes = candidates.flatMap { listOf(it, File(it, "Contents")) + (it.listFiles()?.toList() ?: emptyList()) }
        .filter { File(it, "product-info.json").isFile && File(it, "lib").isDirectory }
    return homes.maxByOrNull { buildNumber(it) }?.absolutePath
}

fun buildNumber(home: File): String =
    Regex("\"buildNumber\"\\s*:\\s*\"([^\"]+)\"").find(File(home, "product-info.json").readText())?.groupValues?.get(1)
        ?.split('.')?.joinToString(".") { it.padStart(8, '0') } ?: ""

val ideHome: String = (findProperty("ideHome") as String?)
    ?: System.getenv("IJFMT_IDE_HOME")
    ?: detectIdeHome()
    ?: throw GradleException("Cannot find an IntelliJ IDEA installation. Pass -PideHome=<path> or set IJFMT_IDE_HOME.")

extra["ideHome"] = ideHome
logger.info("ijfmt: compiling against IntelliJ at $ideHome")

subprojects {
    apply(plugin = "java")
    repositories { mavenCentral() }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial,-options,-classfile,-try", "-Werror"))
    }
}

val pluginJar = project(":daemon").tasks.named<Jar>("jar")
val cliJar = project(":cli").tasks.named<Jar>("jar")

val distribution: CopySpec = copySpec {
    from(cliJar) { into("lib") }
    from(pluginJar) { into("plugin/ijfmt-daemon/lib") }
    from("dist/bin") {
        into("bin")
        filePermissions { unix("rwxr-xr-x") }
    }
    from("lua") { into("lua") }
    from("tests/nvim") { into("tests/nvim") }
    from("README.md")
}

val installDist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles a runnable ijfmt distribution into build/install/ijfmt."
    with(distribution)
    into(layout.buildDirectory.dir("install/ijfmt"))
    // Preserve the client's AppCDS archive between builds.
    preserve { include("lib/*.jsa") }
    doLast {
        // Default JVM for the launcher scripts: the JetBrains Runtime bundled with the IDE we built against.
        val jbr = listOf("jbr", "jbr/Contents/Home", "Contents/jbr/Contents/Home").map { File(ideHome, it) }
            .firstOrNull { File(it, "bin/java").isFile || File(it, "bin/java.exe").isFile }
        if (jbr != null) destinationDir.resolve("lib/java.home").writeText(jbr.absolutePath)
    }
}

val install by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Installs ijfmt into -PinstallDir (default: ~/.ijfmt). Add <installDir>/bin to your PATH."
    dependsOn(installDist)
    from(installDist.map { it.destinationDir }) { exclude("lib/*.jsa") }
    into((findProperty("installDir") as String?) ?: "${System.getProperty("user.home")}/.ijfmt")
    preserve { include("lib/*.jsa") }
}

tasks.named("assemble") { dependsOn(installDist) }
