plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.5.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.0.202606012155-r") {
        // Paper already provides the SLF4J API.
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    shadowJar {
        // Produces SkriptDeploy-version.jar instead of SkriptDeploy-version-all.jar.
        archiveClassifier.set("")

        // Prevent conflicts with JGit copies shaded by other plugins.
        relocate(
            "org.eclipse.jgit",
            "me.mrscopes.skriptdeploy.libs.jgit"
        )

        relocate(
            "com.googlecode.javaewah",
            "me.mrscopes.skriptdeploy.libs.javaewah"
        )
    }

    jar {
        // Prevent the normal unshaded JAR from also being produced.
        enabled = false
    }

    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to project.version)

        inputs.properties(props)

        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}