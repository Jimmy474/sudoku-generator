plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.dataframe") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "com.jimmy"
version = "1.0-SNAPSHOT"

application{
    mainClass.set("com.jimmy.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:dataframe:1.0.0-rc02")
    implementation("org.jetbrains.kotlinx:kandy-lets-plot:0.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-hocon:1.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.jimmy.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
}