plugins {
    java
    id("community.flock.wirespec.spring.extractor") version "@project.version@"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation("org.springframework:spring-webflux:6.1.14")
    implementation("io.projectreactor:reactor-core:3.7.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

wirespecExtractor {
    basePackage.set("com.acme.api")
}
