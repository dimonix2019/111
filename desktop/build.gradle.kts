plugins {
    kotlin("jvm")
    application
}

group = "com.example.moexmvp"
version = "1.0.0"

application {
    mainClass.set("com.example.moexmvp.desktop.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Runnable JAR with dependencies"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.example.moexmvp.desktop.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
}

tasks.register<Exec>("packageWindowsBat") {
    group = "distribution"
    description = "Copy fat JAR next to run-desktop-jar.bat"
    dependsOn("fatJar")
    commandLine("cmd", "/c", "echo packaged")
    doLast {
        val jar = tasks.named<Jar>("fatJar").get().archiveFile.get().asFile
        println("Desktop JAR: ${jar.absolutePath}")
    }
}
