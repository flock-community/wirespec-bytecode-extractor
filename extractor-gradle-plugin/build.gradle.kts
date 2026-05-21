plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.gradle.plugin.publish)
    `maven-publish`
    alias(libs.plugins.vanniktech.maven.publish.base)
}

description = "Gradle plugin: extracts Spring Boot endpoints into Wirespec .ws files."
base.archivesName.set("wirespec-spring-extractor-gradle-plugin")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Plugin runs inside Gradle's runtime (Kotlin 2.0.21 in Gradle 8.10.2).
        // See extractor-core/build.gradle.kts for full rationale.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

gradlePlugin {
    website.set("https://wirespec.io")
    vcsUrl.set("https://github.com/flock-community/wirespec-spring-extractor")
    plugins {
        register("wirespecExtractor") {
            id = "community.flock.wirespec.spring.extractor"
            displayName = "Wirespec Spring Extractor"
            description = project.description
            implementationClass = "community.flock.wirespec.spring.extractor.gradle.WirespecExtractorPlugin"
            tags.set(listOf("wirespec", "spring", "openapi", "schema"))
        }
    }
}

dependencies {
    // Core extraction logic. Consumers resolve it transitively from the published POM.
    implementation(project(":extractor-core"))
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    repositories {
        // Build-local repo used by :integration-tests-gradle. Publishing here
        // keeps the fixture Gradle builds isolated from the user's ~/.m2.
        maven {
            name = "itLocal"
            url = uri(rootProject.layout.buildDirectory.dir("it-repo"))
        }
    }
}
