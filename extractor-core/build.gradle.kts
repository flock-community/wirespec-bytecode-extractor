plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    alias(libs.plugins.vanniktech.maven.publish.base)
}

description = "Spring → Wirespec extraction logic. Maven-agnostic; drives the Maven plugin."
base.archivesName.set("wirespec-spring-extractor-core")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Spring's @PathVariable/@RequestParam (and our extractor) need parameter names.
        freeCompilerArgs.add("-java-parameters")
        // The Gradle plugin embeds extractor-core and runs inside Gradle's runtime,
        // which bundles Kotlin 2.0.21 (Gradle 8.10.2). Pinning api/language version
        // to 2.0 prevents the 2.3 compiler from emitting calls to stdlib overloads
        // introduced after 2.0 (e.g. the single-arg sequenceOf(element: T) added in
        // Kotlin 2.1), which would NoSuchMethodError at runtime inside Gradle.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.classgraph)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    // `api` because WirespecAstBuilder.toEndpoint / toDefinition expose
    // Wirespec compiler AST types in their public signatures — consumers
    // of extractor-core (e.g. ExtractMojo) need those types on the
    // compile classpath.
    api(libs.wirespec.core)
    implementation(libs.wirespec.emitter)
    implementation(libs.spring.web)
    implementation(libs.spring.context)
    implementation(libs.jackson.annotations)
    implementation(libs.jakarta.validation)
    implementation(libs.swagger.annotations)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.reactor.core)
    testImplementation(libs.spring.webflux)
    testImplementation(libs.spring.webmvc)
    // Spring Kafka is referenced ONLY from test code (real annotations/types
    // for the scanner tests). It is deliberately not on the main classpath:
    // extractor-core's scanners use string-based class-name lookups so the
    // extractor cleanly no-ops on projects that don't use Spring Kafka.
    testImplementation(libs.spring.kafka)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    repositories {
        // Same build-local repo as the Maven plugin: integration tests can
        // resolve both artifacts from one place.
        maven {
            name = "itLocal"
            url = uri(rootProject.layout.buildDirectory.dir("it-repo"))
        }
    }
}
