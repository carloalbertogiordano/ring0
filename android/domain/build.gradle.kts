plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

val consoleLauncher: Configuration by configurations.creating

dependencies {
    api(project(":protocol"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    consoleLauncher("org.junit.platform:junit-platform-console-standalone:1.12.2")
}

tasks.test {
    useJUnitPlatform()
}

// Come nel modulo protocol: i worker di test Gradle usano TCP loopback, rifiutato su questa macchina.
tasks.register<JavaExec>("runTests") {
    group = "verification"
    description = "Esegue i test JUnit senza i worker TCP di Gradle"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath + consoleLauncher
    mainClass.set("org.junit.platform.console.ConsoleLauncher")
    args(
        "execute",
        "--scan-classpath", sourceSets["test"].output.classesDirs.asPath,
        "--fail-if-no-tests",
        "--details=tree",
        "--disable-banner",
    )
}
