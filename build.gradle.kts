plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "local.aichat"
version = "1.0.0"

kotlin {
    jvmToolchain(20)
}

application {
    mainClass.set("MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-Daichat.ansi=true"
    )
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    systemProperty("aichat.ansi", "true")
}

tasks.named<Sync>("installDist") {
    destinationDir = layout.buildDirectory.dir("app/AIChatApp").get().asFile
}
