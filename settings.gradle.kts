rootProject.name = "wirespec-extractor"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(":extractor-core")
include(":extractor-maven-plugin")
include(":extractor-gradle-plugin")
include(":integration-tests-maven")
include(":integration-tests-gradle")
