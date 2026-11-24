import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// The exporter runs the runtime's own anatomy code rather than a second implementation, so it
// compiles the pure-Kotlin packages straight out of the app module. Anything that reaches Android
// or Filament is outside the include list and is not compiled here.
sourceSets {
    named("main") {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include(
            "com/monkfitness/app/BuildConfig.kt",
            "com/monkfitness/app/anatomy/**",
            "com/monkfitness/app/animation/**",
            "com/monkfitness/app/poses/**",
            "com/monkfitness/app/character/X1Bind.kt",
            "com/monkfitness/app/character/BoneMap.kt",
            "com/monkfitness/app/character/HelperBindManifest.kt",
            "com/monkfitness/app/character/Mat4.kt",
            "com/monkfitness/app/character/MonkJointResolver.kt",
            "com/monkfitness/app/character/GltfRig.kt",
            "com/monkfitness/app/anatomyexport/**"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("com.monkfitness.app.anatomyexport.AnatomyExport")
}
