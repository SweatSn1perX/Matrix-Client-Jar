import proguard.gradle.ProGuardTask
import net.matrix.build.StringObfuscatorTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.5.0")
    }
}

plugins {
    id("fabric-loom") version "1.15.3"
    id("maven-publish")
}

val minecraft_version: String by project
val yarn_mappings: String by project
val loader_version: String by project
val fabric_version: String by project
val mod_version: String by project
val maven_group: String by project
val archives_base_name: String by project

version = mod_version
group = maven_group

base {
    archivesName.set(archives_base_name)
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft_version")
    mappings("net.fabricmc:yarn:$yarn_mappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loader_version")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabric_version")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${archives_base_name}" }
    }
}

val encryptStrings by tasks.registering {
    val remapJarTask = tasks.remapJar.get()
    dependsOn(remapJarTask)
    
    val inputJar = remapJarTask.archiveFile.get().asFile
    val outputJar = layout.buildDirectory.file("libs/matrix-encrypted.jar").get().asFile
    
    inputs.file(inputJar)
    outputs.file(outputJar)
    
    doLast {
        println("Encrypting strings in " + inputJar.name)
        StringObfuscatorTask.process(inputJar, outputJar)
    }
}

val obfuscateJar by tasks.registering(ProGuardTask::class) {
    configuration("proguard.conf")

    val encryptTask = tasks.named("encryptStrings").get()
    val encryptOutput = encryptTask.outputs.files.singleFile
    injars(encryptOutput)
    dependsOn(encryptTask)

    // Output jar with the requested naming convention
    outjars(layout.buildDirectory.file("libs/matrix_mace.jar"))

    // Libraries (JDK & Dependencies)
    // We use the runtime classpath to ensure ProGuard knows about all library classes
    val libraryFiles = configurations.runtimeClasspath.get().files
    libraryFiles.forEach {
        libraryjars(it)
    }

    // Include the Java standard library from the current JDK
    val javaHome = System.getProperty("java.home")
    if (File("$javaHome/jmods").exists()) {
        libraryjars("$javaHome/jmods")
    } else {
        libraryjars("$javaHome/lib/rt.jar") // For Java 8 and older
    }
}

tasks.build {
    dependsOn(obfuscateJar)
}
