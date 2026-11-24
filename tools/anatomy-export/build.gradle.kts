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
            "com/monkfitness/app/character/HelperBinds.kt",
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

// The two artefacts are separate steps: the manifest is the contract, the mesh binds to it.
tasks.register<JavaExec>("exportHelpers") {
    group = "export"
    description = "Writes app/src/main/assets/anatomy/helpers_v1.json from the solver's rest path."
    mainClass.set("com.monkfitness.app.anatomyexport.AnatomyExport")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("exportMuscles") {
    group = "export"
    description = "Writes app/src/main/assets/characters/muscles_sup.glb, skinned to the helper binds."
    mainClass.set("com.monkfitness.app.anatomyexport.MuscleMeshExport")
    classpath = sourceSets["main"].runtimeClasspath
}
