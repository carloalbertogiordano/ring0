plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ring.app.data"
    compileSdk = 37

    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(21) }

// the exported schemas are what the migration tests compare against
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    api(project(":migrations"))
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.room.compiler)
}
