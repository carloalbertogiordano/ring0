plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
}

// Quality gate for Kotlin: style (ktlint) and code smells, complexity, risky constructs (detekt).
subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt.yml"))
        parallel = true
    }
}

// The ktlint Gradle plugin needs Gradle worker daemons (TCP loopback, unusable on this machine), so run the CLI directly.
val ktlint: Configuration by configurations.creating

dependencies {
    ktlint(libs.ktlint.cli) {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) }
    }
}

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Kotlin code style (ktlint CLI)"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("**/src/**/*.kt", "**/*.kts", "!**/build/**")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Auto-fix Kotlin code style"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("-F", "**/src/**/*.kt", "**/*.kts", "!**/build/**")
}

tasks.register("qualityGate") {
    group = "verification"
    description = "ktlint + detekt on every module"
    dependsOn("ktlintCheck", subprojects.map { "${it.path}:detekt" })
}
