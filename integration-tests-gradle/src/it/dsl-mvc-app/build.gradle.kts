plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.spring.extractor") version "@project.version@"
}

dependencies {
    implementation("org.springframework:spring-webmvc:6.1.14")
    implementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-java-parameters")
    }
}

wirespecExtractor {
    basePackage.set("com.acme.api")
}
