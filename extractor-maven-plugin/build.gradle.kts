import org.gradlex.maven.plugin.development.task.GenerateMavenPluginDescriptorTask

// org.gradlex:maven-plugin-development bundles ASM 9.1 + maven-plugin-tools 3.6.1,
// which can't parse Java 21 class files (`Unsupported class file major version 65`).
// Force the buildscript classpath to a modern ASM that understands them.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force(
                "org.ow2.asm:asm:9.7.1",
                "org.ow2.asm:asm-commons:9.7.1",
                "org.ow2.asm:asm-tree:9.7.1",
                "org.ow2.asm:asm-analysis:9.7.1",
            )
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.plugin.development)
    `maven-publish`
    alias(libs.plugins.vanniktech.maven.publish.base)
}

description = "Extracts Spring Boot endpoints into Wirespec .ws files."
base.archivesName.set("wirespec-bytecode-extractor-maven-plugin")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Required: Maven plugin tooling reflects on @Parameter names.
        freeCompilerArgs.add("-java-parameters")
    }
}

mavenPlugin {
    // Coordinates of the published Maven plugin artifact. The directory name
    // is `plugin/`, but the Maven artifactId is the full descriptive name.
    artifactId.set("wirespec-bytecode-extractor-maven-plugin")
    name.set("Wirespec Bytecode Extractor Maven Plugin")
    // The Maven goal prefix: invoked as `mvn wirespec:extract` (or `wirespec:help`).
    goalPrefix.set("wirespec")
    // Generates the `wirespec:help` mojo (HelpMojo class).
    helpMojoPackage.set("community.flock.wirespec.bytecode.wirespec_bytecode_extractor_maven_plugin")
}

tasks.named<GenerateMavenPluginDescriptorTask>("generateMavenPluginDescriptor") {
    // The gradlex plugin only registers `main.java.classesDirectory` for
    // scanning, which doesn't contain our Kotlin-authored mojos. The
    // descriptor task iterates over classesDirs and calls the maven-plugin-
    // tools scanner once per dir; each call re-scans sourcesDirs as well, so
    // adding multiple class dirs causes the generated HelpMojo.java to be
    // picked up multiple times by the javadoc extractor → duplicate goal.
    //
    // Mojo split in this project:
    //   * ExtractMojo (Kotlin) — uses @Mojo annotations → annotations extractor needs kotlin/main
    //   * HelpMojo  (generated Java) — uses javadoc @goal tags → javadoc extractor needs HelpMojo.java
    //
    // So we point classesDirs at *only* the Kotlin output (single iteration)
    // and rely on the default sourcesDirs to include the generated HelpMojo.java
    // for the javadoc extractor.
    classesDirs.setFrom(sourceSets.main.get().kotlin.classesDirectory)

    // The gradlex maven-plugin-development extension does not model the
    // advisory <requiredJavaVersion> / <requiredMavenVersion> fields that the
    // upstream maven-plugin-plugin writes. Restore them by patching the
    // generated plugin.xml. Maven uses these only to print friendlier errors
    // when a consumer runs on an older JDK or Maven than the plugin supports;
    // the plugin still works without them, but we keep them to match the
    // previous Maven-built artifact.
    doLast {
        val pluginXml = layout.buildDirectory
            .file("mavenPlugin/descriptor/META-INF/maven/plugin.xml").get().asFile
        if (pluginXml.exists()) {
            val text = pluginXml.readText()
            if (!text.contains("<requiredJavaVersion>")) {
                val insertion = "  <requiredJavaVersion>21</requiredJavaVersion>\n" +
                    "  <requiredMavenVersion>3.9.0</requiredMavenVersion>\n"
                pluginXml.writeText(text.replace("  <mojos>", "$insertion  <mojos>"))
            }
        }
    }
}

dependencies {
    // Maven plugin API — provided at runtime by the Maven instance loading the plugin.
    compileOnly(libs.maven.plugin.api)
    compileOnly(libs.maven.core)
    compileOnly(libs.maven.plugin.annotations)

    // Core extraction logic.
    implementation(project(":extractor-core"))

    // Kotlin
    implementation(libs.kotlin.stdlib)

    // Tests
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    // WirespecLifecycleParticipantTest uses org.apache.maven.model.* and MavenProject.
    testImplementation(libs.maven.core)
    testImplementation(libs.maven.plugin.api)
}

tasks.test {
    useJUnitPlatform()
}

// Embed pom.xml + pom.properties inside the jar under
// META-INF/maven/<groupId>/<artifactId>/ — Maven historically writes these into
// every built jar. Gradle's maven-publish doesn't do this by default; the
// resulting absence is functionally harmless, but we keep parity with what the
// previous Maven build shipped.
val pomPropertiesFile = layout.buildDirectory.file("generated/pom-properties/pom.properties")
val writePomProperties = tasks.register("writePomProperties") {
    val groupId = project.group.toString()
    val version = project.version.toString()
    val outputFile = pomPropertiesFile
    inputs.property("groupId", groupId)
    inputs.property("artifactId", "wirespec-bytecode-extractor-maven-plugin")
    inputs.property("version", version)
    outputs.file(outputFile)
    doLast {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "artifactId=wirespec-bytecode-extractor-maven-plugin\n" +
                    "groupId=$groupId\n" +
                    "version=$version\n",
            )
        }
    }
}

tasks.named<Jar>("jar") {
    val embeddedMetaDir = "META-INF/maven/${project.group}/wirespec-bytecode-extractor-maven-plugin"
    into(embeddedMetaDir) {
        from(tasks.named("generatePomFileForMavenPublication"))
        rename { "pom.xml" }
    }
    into(embeddedMetaDir) {
        from(pomPropertiesFile)
    }
    dependsOn(writePomProperties)
}

publishing {
    repositories {
        // Build-local repo used by the :integration-tests-maven module. Publishing
        // here keeps the fixture Maven builds isolated from the user's ~/.m2.
        maven {
            name = "itLocal"
            url = uri(rootProject.layout.buildDirectory.dir("it-repo"))
        }
    }
}
