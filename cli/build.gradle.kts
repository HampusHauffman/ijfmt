plugins {
    `java-library`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    // The client has no dependencies and runs on any Java 17+ runtime.
    options.release = 17
}

tasks.jar {
    archiveFileName = "ijfmt.jar"
    manifest {
        attributes("Main-Class" to "dev.ijfmt.cli.Main", "Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
