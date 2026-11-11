plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

val consoleLauncher: Configuration by configurations.creating

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.gson)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    consoleLauncher("org.junit.platform:junit-platform-console-standalone:1.12.2")
}

tasks.test {
    useJUnitPlatform()
}

// Same as the other JVM modules: Gradle test workers use TCP loopback, which is refused on this machine.
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
