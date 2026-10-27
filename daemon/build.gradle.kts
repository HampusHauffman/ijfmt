val ideHome: String by rootProject.extra

dependencies {
    // Compile against the exact IDE we run inside. Nothing is bundled: the plugin uses the IDE's own classes at runtime.
    compileOnly(fileTree("$ideHome/lib") { include("*.jar") })
}

tasks.withType<JavaCompile>().configureEach {
    // IntelliJ 2024.1+ runs on Java 21+.
    options.release = 21
    // Platform APIs are routinely deprecated/experimental; that's expected for an IDE-internal integration.
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
}

tasks.processResources {
    val version = project.version.toString()
    // The plugin is compiled against this exact IDE; don't claim compatibility with older builds.
    val sinceBuild = Regex("\"buildNumber\"\\s*:\\s*\"(\\d+)").find(File(ideHome, "product-info.json").readText())
        ?.groupValues?.get(1) ?: "262"
    inputs.property("version", version)
    inputs.property("sinceBuild", sinceBuild)
    filesMatching("META-INF/plugin.xml") { expand("version" to version, "sinceBuild" to sinceBuild) }
}

tasks.jar {
    archiveFileName = "ijfmt-daemon.jar"
}
