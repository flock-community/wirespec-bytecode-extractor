pluginManagement {
    repositories {
        maven { url = uri("@itRepo@") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("@itRepo@") }
        mavenCentral()
    }
}

rootProject.name = "dsl-mvc-app"
