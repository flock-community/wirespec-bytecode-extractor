pluginManagement {
    repositories {
        maven { url = uri("@itRepo@") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("@itRepo@") }
    }
}

rootProject.name = "kafka-app"
