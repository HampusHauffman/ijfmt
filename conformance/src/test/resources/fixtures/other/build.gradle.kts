plugins {
    java
    id("com.diffplug.spotless") version "7.0.0"
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.0"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<JavaCompile>().configureEach { options.release = 21; options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror")) }
