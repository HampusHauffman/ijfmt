dependencies {
    testImplementation(project(":cli"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

// The conformance suite launches real IntelliJ instances and takes minutes, so it is not part of `check`.
tasks.test {
    enabled = false
}

val corpusDir = File(System.getProperty("user.home"), ".cache/ijfmt-corpus/intellij-community")
// JetBrains' own formatter test inputs plus real-world Java sources, pinned for reproducible runs.
val corpusCommit = "4a254f6b32159777db807891ecbf92a6eec44c72"
val corpusPaths = listOf("java/java-tests/testData/psi/formatter", "java/java-psi-api/src")

val fetchCorpus by tasks.registering {
    group = "verification"
    description = "Sparse-checkouts IntelliJ's formatter test data (and some real Java sources) as a conformance corpus."
    outputs.upToDateWhen { corpusPaths.all { File(corpusDir, it).isDirectory } }
    doLast {
        fun git(vararg args: String, dir: File = corpusDir) {
            val process = ProcessBuilder(listOf("git") + args).directory(dir).inheritIO().start()
            check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed" }
        }
        if (!File(corpusDir, ".git").isDirectory) {
            corpusDir.mkdirs()
            git("init", "--quiet")
            git("remote", "add", "origin", "https://github.com/JetBrains/intellij-community.git")
            git("config", "core.sparseCheckout", "true")
        }
        git("sparse-checkout", "set", *corpusPaths.toTypedArray())
        git("fetch", "--quiet", "--depth", "1", "--filter=blob:none", "origin", corpusCommit)
        git("checkout", "--quiet", corpusCommit)
    }
}

val conformance by tasks.registering(Test::class) {
    group = "verification"
    description = "Differential test: ijfmt output vs. IntelliJ's stock command line formatter on a corpus (see README)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    dependsOn(rootProject.tasks.named("installDist"))
    outputs.upToDateWhen { false }
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
        showStandardStreams = true
    }
    val ideHome: String by rootProject.extra
    systemProperty("ijfmt.ideHome", ideHome)
    systemProperty("ijfmt.dist", rootProject.layout.buildDirectory.dir("install/ijfmt").get().asFile.absolutePath)
    systemProperty("ijfmt.conformance.resources", file("src/test/resources").absolutePath)
    systemProperty("ijfmt.conformance.externalCorpus", corpusDir.absolutePath)
    findProperty("conformance.work")?.let { systemProperty("ijfmt.work", it) }
    listOf("corpus", "variants", "mutations", "extensions", "maxFiles", "seed").forEach { key ->
        findProperty("conformance.$key")?.let { systemProperty("ijfmt.conformance.$key", it) }
    }
}
