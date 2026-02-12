plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "dev.runningqueries"
version = "1.0.0"

val dataSpellPath: String by project

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        local(dataSpellPath)
        bundledPlugin("com.intellij.database")
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    patchPluginXml {
        sinceBuild.set("253")
        untilBuild.set("253.*")
    }
}
