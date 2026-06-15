// Root project: this is a multi-module repository.
// Per-module configuration lives in `extractor-maven-plugin/build.gradle.kts`,
// `extractor-core/build.gradle.kts`, `extractor-gradle-plugin/build.gradle.kts`,
// and `integration-tests-{maven,gradle}/build.gradle.kts`.
// Versions and shared coordinates are declared in `gradle.properties` and
// `gradle/libs.versions.toml`.
//
// Maven Central publishing for the three library subprojects is configured
// here via com.vanniktech.maven.publish.base. Each subproject applies the
// `.base` plugin itself (so vanniktech's apply-time code runs with the
// subproject's buildscript classloader, which has the kotlin / java-gradle-
// plugin classes it needs to reference). The root only owns the *shared*
// configuration (POM metadata, coordinates, signing, Central Portal
// destination) so we don't repeat it three times.
//
// `apply false` puts vanniktech's jar on the root buildscript classpath so
// the MavenPublishBaseExtension / MavenPom types resolve below, without
// actually applying the plugin to the root project.

plugins {
    // Both declared `apply false` so the root project itself doesn't apply
    // them — they're applied by each subproject's own `plugins { }` block.
    // We need them on the root buildscript classpath nevertheless: vanniktech
    // touches `KotlinBasePlugin` directly (checkcast) at apply-time, and the
    // class must be reachable from the classloader that loaded vanniktech.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.vanniktech.maven.publish.base) apply false
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish.base") {
        val publishedArtifactId = when (name) {
            "extractor-core"          -> "wirespec-extractor-core"
            "extractor-maven-plugin"  -> "wirespec-extractor-maven-plugin"
            "extractor-gradle-plugin" -> "wirespec-extractor-gradle-plugin"
            else                      -> error("vanniktech applied to unexpected subproject: $name")
        }
        val isMavenPlugin = name == "extractor-maven-plugin"

        // `plugins.withId` fires when vanniktech.base is applied, which is
        // last in each subproject's plugins block (after kotlin, java-gradle-
        // plugin, etc.). configureBasedOnAppliedPlugins runs synchronously
        // here and creates the publication eagerly — the subproject's
        // `tasks.named<Jar>("jar") { from(tasks.named("generatePomFile...")) }`
        // (in extractor-maven-plugin) then finds the POM task. afterEvaluate
        // would defer publication creation past that lookup → "task not found".
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            configureBasedOnAppliedPlugins()
            // vanniktech 0.30.0 defaults `host` to SonatypeHost.DEFAULT
            // (https://oss.sonatype.org — legacy Nexus 2). Our Sonatype account
            // exists only on the new Central Portal (central.sonatype.com), so
            // we must pass CENTRAL_PORTAL explicitly — otherwise the publish
            // task hits the legacy staging API and gets 402 Payment Required.
            publishToMavenCentral(
                com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
                automaticRelease = true,
            )
            signAllPublications()

            coordinates(
                groupId = project.group.toString(),
                artifactId = publishedArtifactId,
                version = project.version.toString(),
            )

            pom {
                name.set(publishedArtifactId)
                description.set(provider { project.description ?: publishedArtifactId })
                url.set("https://github.com/flock-community/wirespec-extractor")
                if (isMavenPlugin) {
                    packaging = "maven-plugin"
                }
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("wilmveel")
                        name.set("Willem Veelenturf")
                        email.set("willem.veelenturf@flock.community")
                        organization.set("Flock. Community")
                        organizationUrl.set("https://flock.community")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/flock-community/wirespec-extractor.git")
                    developerConnection.set("scm:git:ssh://github.com:flock-community/wirespec-extractor.git")
                    url.set("https://github.com/flock-community/wirespec-extractor")
                }
            }
        }
    }
}
