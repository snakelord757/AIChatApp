plugins {
    kotlin("jvm") version "2.2.21"
    application
    id("org.graalvm.buildtools.native") version "1.1.1"
}

group = "local.aichat"
version = "1.16.0"

val buildJavaVersion = providers.gradleProperty("buildJavaVersion")
    .map(String::toInt)
    .orElse(21)

kotlin {
    jvmToolchain(buildJavaVersion.get())
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    applicationName = "aichat"
    mainClass.set("cli.AiChatCliKt")
    applicationDefaultJvmArgs = listOf(
        "-Daichat.ansi=true"
    )
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    systemProperty("aichat.ansi", "true")
    System.getProperty("aichat.history.dir")?.takeIf { it.isNotBlank() }?.let {
        systemProperty("aichat.history.dir", it)
    }
    outputs.upToDateWhen { false }
}

tasks.named<Sync>("installDist") {
    into(layout.buildDirectory.dir("app/aichat"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("printProjectVersion") {
    group = "help"
    description = "Prints the project version for CI release tagging."
    doLast {
        println(project.version)
    }
}

graalvmNative {
    toolchainDetection.set(false)

    binaries {
        named("main") {
            imageName.set("aichat")
            mainClass.set("cli.AiChatCliKt")
            fallback.set(false)
            sharedLibrary.set(false)
            buildArgs.addAll(
                listOf(
                    "--enable-url-protocols=http,https",
                    "-H:+AddAllCharsets"
                )
            )
        }
    }
}
