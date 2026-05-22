plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.spring.extractor") version "@project.version@"
}

dependencies {
    implementation("org.springframework.kafka:spring-kafka:3.2.4")
    implementation("org.springframework:spring-messaging:6.1.14")
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
