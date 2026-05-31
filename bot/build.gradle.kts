import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.aipirateradio.bot.BotMainKt")
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("net.dv8tion:JDA:6.4.1")
    implementation("org.json:json:20240303")
    implementation("moe.kyokobot.libdave:adapter-jda:0.1.2")
    implementation("moe.kyokobot.libdave:impl-jni:0.1.2")
    implementation("moe.kyokobot.libdave:natives-win-x86-64:0.1.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}
