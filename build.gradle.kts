plugins {
    java
}

group = "dev.bountysystem"
description = "Advanced player bounty system for Paper."
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    // Server provides Paper at runtime; never bundled into the plugin jar.
    compileOnly("io.papermc.paper:paper-api:26.3.build.214-stable")
    // Optional Vault economy bridge — only its API jar (its transitive old Bukkit
    // dependency would collide with Paper API).
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        isTransitive = false
    }
    // Test sources also compile against Paper types (e.g. JavaPlugin in BountyPlugin).
    testImplementation("io.papermc.paper:paper-api:26.3.build.214-stable")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Real SQLite driver for storage tests; the production plugin loads it via plugin.yml libraries.
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            filteringCharset = "UTF-8"
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }
}
