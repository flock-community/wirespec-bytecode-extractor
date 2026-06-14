plugins {
    kotlin("jvm") version "2.1.20"
    // Substituted by the test runner with the plugin version under test.
    id("community.flock.wirespec.bytecode.extractor") version "@project.version@"
}

dependencies {
    implementation("org.springframework:spring-web:6.1.14")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // So Spring's @PathVariable/@RequestParam (and our extractor) can recover
        // parameter names that have no explicit value().
        freeCompilerArgs.add("-java-parameters")
    }
}

wirespecExtractor {
    basePackage.set("com.acme.api")
}
