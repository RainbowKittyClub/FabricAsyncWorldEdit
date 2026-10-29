import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RunGameTask

plugins {
    id("net.fabricmc.fabric-loom")
    `java-library`
    id("buildlogic.platform")
}

platform {
    kind = buildlogic.WorldEditKind.Mod
    includeClasspath = true
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "25"
    targetCompatibility = "25"
    options.release.set(25)
}

loom {
    accessWidenerPath.set(project.file("src/upstream/resources/worldedit.accesswidener"))
}

tasks.withType<RunGameTask>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}

sourceSets {
    named("main") {
        java.setSrcDirs(listOf("src/upstream/java"))
        resources.setSrcDirs(listOf("src/upstream/resources"))
    }
    create("testmod") {
        java.setSrcDirs(listOf("src/testmod/java"))
        resources.setSrcDirs(listOf("src/testmod/resources"))
        compileClasspath += named("main").get().output + named("main").get().compileClasspath
        runtimeClasspath += named("main").get().output + named("main").get().runtimeClasspath
    }
}

loom {
    mods {
        create("worldedit") { sourceSet(sourceSets["main"]) }
        create("worldedit-sp-testharness") { sourceSet(sourceSets["testmod"]) }
    }
    runs {
        create("testmodClient") {
            client()
            source(sourceSets["testmod"])
            programArgs("--width", "854", "--height", "480")
            if (project.hasProperty("quickPlayWorld")) {
                programArgs("--quickPlaySingleplayer", project.property("quickPlayWorld") as String)
            }
        }
    }
}

repositories {
    // Loom adds project repositories, which take precedence over the settings repositories.
    maven {
        name = "EngineHub Repository"
        url = uri("https://maven.enginehub.org/repo/")
        content { excludeGroup("net.kyori") }
    }
    verifyEngineHubRepositories()
}

dependencies {
    "api"(project(":worldedit-core"))
    "minecraft"(libs.fabric.minecraft)
    "implementation"(libs.fabric.loader)
    "implementation"(libs.cuiProtocol.fabric)
    "implementation"(libs.cuiProtocol.common)
    "include"(libs.cuiProtocol.fabric) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class, Bundling.SHADOWED))
        }
    }
    for (dep in listOf(libs.parallelgzip, libs.sparsebitset, libs.zstd, libs.json.simple,
        libs.jchronic, libs.snakeyaml)) {
        "include"(dep)
        "implementation"(dep)
    }
    // Vanilla supplies lz4. ZSTD must remain unrelocated for its native loader.
    @Suppress("UNCHECKED_CAST")
    val fabricModJson = file("src/upstream/resources/fabric.mod.json").bufferedReader().use {
        groovy.json.JsonSlurper().parse(it) as Map<String, Map<String, *>>
    }
    val wantedDependencies = (fabricModJson["depends"] ?: error("no depends in fabric.mod.json")).keys
        .filter { it == "fabric-api-base" || it.contains(Regex("v\\d$")) }
    for (wantedDependency in wantedDependencies) {
        val dep = fabricApi.module(wantedDependency, libs.versions.fabric.api.get())
        "include"(dep)
        "implementation"(dep)
    }
    "compileOnly"(libs.fabric.permissions.api)
    "compileOnly"(libs.errorprone.annotations)
}

configure<BasePluginExtension> {
    archivesName.set("${project.name}-mc${libs.fabric.minecraft.get().version}")
}

plugins.withId("maven-publish") {
    configure<PublishingExtension> {
        publications.named<MavenPublication>("maven") {
            artifactId = the<BasePluginExtension>().archivesName.get()
            from(components["java"])
        }
    }
}

tasks.named<Copy>("processResources") {
    val internalVersion = project.ext["internalVersion"]
    val properties = mapOf("version" to internalVersion,
        "loaderVersion" to libs.fabric.loader.get().version,
        "minecraftVersion" to libs.fabric.minecraft.get().version)
    inputs.properties(properties)
    filesMatching("fabric.mod.json") { expand(properties) }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("dist")
    dependsOn(tasks.jar)
    val processedModJson = layout.buildDirectory.file("resources/main/fabric.mod.json").get().asFile.absoluteFile
    eachFile {
        if (path == "fabric.mod.json" && file.absoluteFile == processedModJson) exclude()
    }
    from(zipTree(tasks.jar.flatMap { it.archiveFile }))
    relocate("org.antlr.v4", "com.sk89q.worldedit.antlr4")
    relocate("net.royawesome.jlibnoise", "com.sk89q.worldedit.jlibnoise")
    dependencies {
        include(dependency("org.antlr:antlr4-runtime"))
        include(dependency("com.sk89q.lib:jlibnoise"))
    }
}

tasks.register<Jar>("testmodJar") {
    archiveClassifier.set("testmod")
    from(sourceSets["testmod"].output)
}

tasks.named("assemble") { dependsOn("shadowJar") }
