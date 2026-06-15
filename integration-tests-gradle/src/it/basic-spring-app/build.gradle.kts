plugins {
    java
    id("community.flock.wirespec.extractor") version "@project.version@"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation("org.springframework:spring-web:6.1.14")
}

// So Spring's @PathVariable/@RequestParam (and our extractor) can recover
// parameter names that have no explicit value().
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

wirespecExtractor {
    basePackage.set("com.acme.api")
}
