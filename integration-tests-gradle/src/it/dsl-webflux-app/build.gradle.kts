plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.spring.extractor") version "@project.version@"
}

dependencies {
    implementation("org.springframework:spring-webflux:6.1.14")
    implementation("io.projectreactor:reactor-core:3.7.0")
    implementation("io.swagger.core.v3:swagger-annotations:2.2.28")
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
