plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.spring.extractor") version "@project.version@"
}

dependencies {
    implementation("org.springframework:spring-jms:6.1.14")
    implementation("org.springframework.amqp:spring-rabbit:3.1.7")
    implementation("org.springframework.pulsar:spring-pulsar:1.0.8")
    implementation("org.springframework.integration:spring-integration-core:6.2.7")
    implementation("org.springframework:spring-messaging:6.1.14")
    implementation("org.springframework:spring-context:6.1.14")
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
