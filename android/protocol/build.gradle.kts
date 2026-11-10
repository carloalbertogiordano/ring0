plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

val consoleLauncher: Configuration by configurations.creating

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    consoleLauncher("org.junit.platform:junit-platform-console-standalone:1.12.2")
}

tasks.test {
    useJUnitPlatform()
}

// The standard `test` task uses TCP loopback workers, which Gradle refuses on this machine (see gradle.properties).
// `runTests` launches the JUnit Console as a plain child process.
tasks.register<JavaExec>("runTests") {
    group = "verification"
    description = "Runs the JUnit tests without Gradle TCP workers"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath + consoleLauncher
    mainClass.set("org.junit.platform.console.ConsoleLauncher")
    args(
        "execute",
        "--scan-classpath",
        sourceSets["test"].output.classesDirs.asPath,
        "--fail-if-no-tests",
        "--details=tree",
        "--disable-banner",
    )
}
