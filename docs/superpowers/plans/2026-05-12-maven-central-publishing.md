# Maven Central Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `extractor-core`, `extractor-maven-plugin`, and `extractor-gradle-plugin` to publish to Sonatype Central Portal from GitHub Actions, triggered by a published GitHub Release; add a separate CI workflow that builds + tests on PRs and pushes to `main`.

**Architecture:** Apply the `com.vanniktech.maven.publish` Gradle plugin to the three publishable subprojects from the root build script. The plugin handles Central Portal upload, in-memory PGP signing, javadoc/sources jar generation, and POM defaults; we set coordinates and shared metadata once in `subprojects {}`. For `extractor-gradle-plugin`, vanniktech auto-detects the `java-gradle-plugin` setup and publishes both the main jar (`pluginMaven`) and the plugin marker (`*PluginMarkerMaven`). The release workflow checks out the tag, overrides `-Pversion=` from the tag name, and runs `publishAndReleaseToMavenCentral`. Secrets reach Gradle as `ORG_GRADLE_PROJECT_*` environment variables.

**Tech Stack:** Gradle 8.x (Kotlin DSL), `com.vanniktech.maven.publish` 0.30.0, GitHub Actions, Sonatype Central Portal, GPG (in-memory armored key).

**Spec:** `docs/superpowers/specs/2026-05-12-maven-central-publishing-design.md`

---

## File map

| Path | Action | Purpose |
|---|---|---|
| `LICENSE` | Create | Top-level Apache 2.0 license text. |
| `gradle/libs.versions.toml` | Modify | Add `vanniktech-maven-publish` to `[versions]` and `[plugins]`. |
| `gradle.properties` | Modify | Append `SONATYPE_HOST` and `RELEASE_SIGNING_ENABLED`. |
| `build.gradle.kts` (root) | Modify (rewrite) | Apply vanniktech to three subprojects; declare POM metadata once. |
| `extractor-core/build.gradle.kts` | Modify | Delete `publications { ... }` block from the `publishing { ... }` block; keep `repositories { ... }`. |
| `extractor-maven-plugin/build.gradle.kts` | Modify | Same removal; keep `repositories { ... }`. The `packaging = "maven-plugin"` POM tweak now lives in the root config. |
| `extractor-gradle-plugin/build.gradle.kts` | Modify | Delete the `publications.withType<MavenPublication>().configureEach { ... }` artifactId tweak — vanniktech's `coordinates()` in the root config applies it to `pluginMaven`. Keep `repositories { ... }`. |
| `.github/workflows/ci.yml` | Create | Build + test on PRs and pushes to `main`. |
| `.github/workflows/release.yml` | Create | Publish to Central Portal on `release: [published]`. |

Task order matches dependency: foundational files first (LICENSE, version catalog, properties), then the build-script rewrite that *uses* those entries, then the workflows that *use* the build script. Each task is one logical commit.

---

## Task 1: Add LICENSE file (Apache 2.0)

**Files:**
- Create: `LICENSE`

- [ ] **Step 1: Fetch the Apache 2.0 license text**

Run from repo root:

```bash
curl -fsSL https://www.apache.org/licenses/LICENSE-2.0.txt -o LICENSE
```

- [ ] **Step 2: Verify the file**

Run: `head -1 LICENSE && wc -l LICENSE`

Expected output (exact):

```
                                 Apache License
202 LICENSE
```

(The 202-line count is fixed for the canonical text; a different line count means the upstream changed shape or curl failed — abort and investigate.)

- [ ] **Step 3: Commit**

```bash
git add LICENSE
git commit -m "docs: add Apache 2.0 LICENSE"
```

---

## Task 2: Add vanniktech plugin to the version catalog

**Files:**
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add the version entry**

Edit `gradle/libs.versions.toml`. Add this line at the end of the `[versions]` block (after `kotest = "5.9.1"`):

```toml
vanniktech-maven-publish = "0.30.0"
```

- [ ] **Step 2: Add the plugin entry**

In the same file, add this line at the end of the `[plugins]` block (after the `maven-plugin-development` line):

```toml
vanniktech-maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktech-maven-publish" }
```

- [ ] **Step 3: Verify the catalog still parses**

Run: `./gradlew help -q`

Expected: exit code 0, no Gradle error. (If the catalog is malformed, Gradle prints `org.gradle.api.InvalidUserDataException: Problem: In version catalog libs, ...` and fails immediately.)

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "build: add vanniktech maven-publish to version catalog"
```

---

## Task 3: Add Sonatype + signing config to `gradle.properties`

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Append the two properties**

Append to `gradle.properties` (after the existing `kotlin.code.style=official` line, leaving a blank line for readability):

```properties

# Maven Central publishing (vanniktech)
SONATYPE_HOST=CENTRAL_PORTAL
RELEASE_SIGNING_ENABLED=true
```

- [ ] **Step 2: Verify Gradle still loads them**

Run: `./gradlew help -q`

Expected: exit code 0. (No build-script change yet, so this only validates the properties file syntax.)

- [ ] **Step 3: Commit**

```bash
git add gradle.properties
git commit -m "build: declare Central Portal endpoint and release signing"
```

---

## Task 4: Wire vanniktech in root + clean subprojects

This task makes four coupled edits — the root and three subprojects — that together replace the existing per-module publishing configuration with a single root-driven configuration. Verification at the end runs the full build to confirm `:integration-tests-maven` and `:integration-tests-gradle` still resolve the locally published artifacts.

**Files:**
- Modify: `build.gradle.kts` (root) — currently a comments-only stub.
- Modify: `extractor-core/build.gradle.kts:42-61` — remove the `publications {}` block.
- Modify: `extractor-maven-plugin/build.gradle.kts:147-170` — remove the `publications {}` block.
- Modify: `extractor-gradle-plugin/build.gradle.kts:47-65` — remove the `publications.withType<MavenPublication>().configureEach { ... }` block.

- [ ] **Step 1: Rewrite the root `build.gradle.kts`**

Replace the entire contents of `build.gradle.kts` (root) with:

```kotlin
// Root project: this is a multi-module repository.
// Per-module configuration lives in `extractor-maven-plugin/build.gradle.kts`,
// `extractor-core/build.gradle.kts`, `extractor-gradle-plugin/build.gradle.kts`,
// and `integration-tests-{maven,gradle}/build.gradle.kts`.
// Versions and shared coordinates are declared in `gradle.properties` and
// `gradle/libs.versions.toml`.
//
// Maven Central publishing for the three library subprojects is configured here
// via com.vanniktech.maven.publish so POM metadata and signing live in one place.

plugins {
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

subprojects {
    if (name in setOf("extractor-core", "extractor-maven-plugin", "extractor-gradle-plugin")) {
        apply(plugin = "com.vanniktech.maven.publish")

        val publishedArtifactId = when (name) {
            "extractor-core"          -> "wirespec-bytecode-extractor-core"
            "extractor-maven-plugin"  -> "wirespec-bytecode-extractor-maven-plugin"
            "extractor-gradle-plugin" -> "wirespec-bytecode-extractor-gradle-plugin"
            else                      -> error("unreachable: $name")
        }
        val isMavenPlugin = name == "extractor-maven-plugin"

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()

            coordinates(
                groupId = project.group.toString(),
                artifactId = publishedArtifactId,
                version = project.version.toString(),
            )

            pom {
                name.set(publishedArtifactId)
                description.set(provider { project.description ?: publishedArtifactId })
                url.set("https://github.com/flock-community/wirespec-bytecode-extractor")
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
                    connection.set("scm:git:git://github.com/flock-community/wirespec-bytecode-extractor.git")
                    developerConnection.set("scm:git:ssh://github.com:flock-community/wirespec-bytecode-extractor.git")
                    url.set("https://github.com/flock-community/wirespec-bytecode-extractor")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Strip the `publications {}` block from `extractor-core/build.gradle.kts`**

Edit `extractor-core/build.gradle.kts`. Replace this exact block:

```kotlin
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "wirespec-bytecode-extractor-core"
            pom {
                name.set("Wirespec Bytecode Extractor Core")
                description.set(project.description)
            }
        }
    }
    repositories {
```

…with:

```kotlin
publishing {
    repositories {
```

(The lines from `repositories {` onward — including the comment, the `maven { ... }` block, and the trailing `}}` braces — stay unchanged.)

- [ ] **Step 3: Strip the `publications {}` block from `extractor-maven-plugin/build.gradle.kts`**

Edit `extractor-maven-plugin/build.gradle.kts`. Replace this exact block:

```kotlin
publishing {
    publications {
        // Single Maven publication of the plugin jar, with the corrected
        // artifactId (project name is `plugin/`, but we publish under the
        // descriptive Maven coordinate).
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "wirespec-bytecode-extractor-maven-plugin"
            pom {
                name.set("Wirespec Bytecode Extractor Maven Plugin")
                description.set(project.description)
                packaging = "maven-plugin"
            }
        }
    }
    repositories {
```

…with:

```kotlin
publishing {
    repositories {
```

(The `repositories { maven { name = "itLocal" ... } }` block stays.)

- [ ] **Step 3b: Strip the artifactId-tweak block from `extractor-gradle-plugin/build.gradle.kts`**

Edit `extractor-gradle-plugin/build.gradle.kts`. Replace this exact block:

```kotlin
// Configure the auto-generated `pluginMaven` publication (jar) + `<id>PluginMarkerMaven`
// (marker) created by java-gradle-plugin. We override the jar's artifactId so the
// Maven coordinate matches the descriptive `wirespec-bytecode-extractor-gradle-plugin`
// rather than the module name.
publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = "wirespec-bytecode-extractor-gradle-plugin"
            pom {
                name.set("Wirespec Bytecode Extractor Gradle Plugin")
                description.set(project.description)
            }
        }
    }
    repositories {
```

…with:

```kotlin
publishing {
    repositories {
```

(The `repositories { maven { name = "itLocal" ... } }` block stays. The artifactId override moves to the root build's `coordinates()` call, which applies it to `pluginMaven` for both `itLocal` and Maven Central publishing.)

- [ ] **Step 4: Verify Gradle scripts parse and the publish tasks exist**

Run: `./gradlew :extractor-core:tasks --all -q | grep -E "publish(ToMavenLocal|MavenPublicationToItLocalRepository|AndReleaseToMavenCentral)"`

Expected output contains all three task names:

```
publishAndReleaseToMavenCentral
publishMavenPublicationToItLocalRepository
publishToMavenLocal
```

Then the same check for the plugin module:

```bash
./gradlew :extractor-maven-plugin:tasks --all -q | grep -E "publish(ToMavenLocal|MavenPublicationToItLocalRepository|AndReleaseToMavenCentral)"
```

Expected: same three task names appear. (If `publishMavenPublicationToItLocalRepository` is missing, the integration tests will break — that's the canary.)

- [ ] **Step 5: Verify the generated POM contains the required Central Portal fields**

Run: `./gradlew :extractor-core:generatePomFileForMavenPublication -q && cat extractor-core/build/publications/maven/pom-default.xml`

Expected: the output XML contains all of these substrings (you can grep them):

- `<url>https://github.com/flock-community/wirespec-bytecode-extractor</url>`
- `<name>The Apache License, Version 2.0</name>`
- `<id>wilmveel</id>`
- `<connection>scm:git:git://github.com/flock-community/wirespec-bytecode-extractor.git</connection>`
- `<artifactId>wirespec-bytecode-extractor-core</artifactId>`

One-shot grep:

```bash
grep -c -E "wirespec-bytecode-extractor-core|Apache License, Version 2.0|wilmveel|flock-community/wirespec-bytecode-extractor" \
  extractor-core/build/publications/maven/pom-default.xml
```

Expected: a number `>= 5` (multiple matches per term are fine). If any term is missing the count drops below the unique-term count of 4 — investigate.

Repeat for the plugin and additionally check `packaging`:

```bash
./gradlew :extractor-maven-plugin:generatePomFileForMavenPublication -q
grep -E "<packaging>maven-plugin</packaging>|<artifactId>wirespec-bytecode-extractor-maven-plugin</artifactId>" \
  extractor-maven-plugin/build/publications/maven/pom-default.xml
```

Expected: both lines present.

Repeat for the Gradle plugin (different publication name — `pluginMaven`, generated by `java-gradle-plugin`):

```bash
./gradlew :extractor-gradle-plugin:generatePomFileForPluginMavenPublication -q
grep -c -E "wirespec-bytecode-extractor-gradle-plugin|Apache License, Version 2.0|wilmveel|flock-community/wirespec-bytecode-extractor" \
  extractor-gradle-plugin/build/publications/pluginMaven/pom-default.xml
```

Expected: a number `>= 4`. If the artifactId is missing or shows `extractor-gradle-plugin` instead of `wirespec-bytecode-extractor-gradle-plugin`, vanniktech's `coordinates()` didn't apply — investigate.

- [ ] **Step 6: Run the full build to confirm nothing else broke**

Run: `./gradlew build --stacktrace`

Expected: `BUILD SUCCESSFUL`. This exercises the unit tests for all three modules, the Maven integration tests under `:integration-tests-maven` (which depend on `publishMavenPublicationToItLocalRepository`), and the Gradle integration tests under `:integration-tests-gradle` (which depend on `publishAllPublicationsToItLocalRepository`) — the definitive check that the publishing rewrite did not regress the existing flow.

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts extractor-core/build.gradle.kts extractor-maven-plugin/build.gradle.kts extractor-gradle-plugin/build.gradle.kts
git commit -m "build: publish via vanniktech maven-publish to Central Portal

Move POM metadata (url, license, scm, developers) into the root build
script applied to extractor-core, extractor-maven-plugin, and
extractor-gradle-plugin via subprojects{}. Remove the per-module
publications blocks; vanniktech generates the maven (or pluginMaven)
publication, sources/javadoc jars, and signing. Keep the itLocal
repositories block in each subproject so the integration-tests-maven
and integration-tests-gradle modules can still resolve the freshly
built artifacts."
```

---

## Task 5: Add the CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Create the workflow directory and file**

Create `.github/workflows/ci.yml` with this exact content:

```yaml
name: CI

on:
  pull_request:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew build --stacktrace
```

- [ ] **Step 2: Verify YAML syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"`

Expected: exit code 0, no output. (A syntax error prints a `yaml.YAMLError` stack trace.)

- [ ] **Step 3: Verify structural fields**

Run:

```bash
python3 -c "
import yaml
w = yaml.safe_load(open('.github/workflows/ci.yml'))
assert w['name'] == 'CI'
# In YAML, the 'on' key parses as Python True unless quoted; PyYAML uses True/None/False as keys.
on = w.get(True) or w.get('on')
assert 'pull_request' in on and 'push' in on
assert on['push']['branches'] == ['main']
job = w['jobs']['build']
assert job['runs-on'] == 'ubuntu-latest'
steps = job['steps']
assert any(s.get('uses', '').startswith('actions/checkout@') for s in steps)
assert any(s.get('uses', '').startswith('actions/setup-java@') for s in steps)
assert any(s.get('uses', '').startswith('gradle/actions/setup-gradle@') for s in steps)
assert any('./gradlew build' in s.get('run', '') for s in steps)
print('ci.yml: ok')
"
```

Expected output: `ci.yml: ok`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add build workflow for PRs and main"
```

---

## Task 6: Add the release workflow

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create the release workflow**

Create `.github/workflows/release.yml` with this exact content:

```yaml
name: Release

on:
  release:
    types: [published]

jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.release.tag_name }}
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Publish to Maven Central
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.SONATYPE_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.SONATYPE_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.GPG_PASSPHRASE }}
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          ./gradlew \
            -Pversion="$VERSION" \
            publishAndReleaseToMavenCentral --no-configuration-cache
```

- [ ] **Step 2: Verify YAML syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"`

Expected: exit code 0, no output.

- [ ] **Step 3: Verify structural fields and the secret mapping**

Run:

```bash
python3 -c "
import yaml
w = yaml.safe_load(open('.github/workflows/release.yml'))
assert w['name'] == 'Release'
on = w.get(True) or w.get('on')
assert on['release']['types'] == ['published']
job = w['jobs']['publish']
assert job['runs-on'] == 'ubuntu-latest'
assert job['permissions']['contents'] == 'read'

# Find the publish step and check env mapping is complete.
publish_step = next(s for s in job['steps'] if 'gradlew' in s.get('run', ''))
env = publish_step['env']
expected = {
    'ORG_GRADLE_PROJECT_mavenCentralUsername':       '\${{ secrets.SONATYPE_USERNAME }}',
    'ORG_GRADLE_PROJECT_mavenCentralPassword':       '\${{ secrets.SONATYPE_PASSWORD }}',
    'ORG_GRADLE_PROJECT_signingInMemoryKey':         '\${{ secrets.GPG_PRIVATE_KEY }}',
    'ORG_GRADLE_PROJECT_signingInMemoryKeyPassword': '\${{ secrets.GPG_PASSPHRASE }}',
}
for k, v in expected.items():
    assert env[k] == v, (k, env[k], v)
assert 'publishAndReleaseToMavenCentral' in publish_step['run']
assert '--no-configuration-cache' in publish_step['run']
assert 'GITHUB_REF_NAME#v' in publish_step['run']
print('release.yml: ok')
"
```

Expected output: `release.yml: ok`.

(Note: the `\${{ ... }}` escapes in the Python snippet are required because the shell evaluates `${{ }}` even inside double quotes when it contains `$`. Run the snippet as written.)

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: publish to Maven Central on GitHub Release

Triggered when a GitHub Release is published. Strips the leading 'v'
from the tag name to build -Pversion, then runs
publishAndReleaseToMavenCentral which uploads to the Sonatype Central
Portal and auto-promotes on validation success. Reads four GitHub
secrets (SONATYPE_USERNAME, SONATYPE_PASSWORD, GPG_PRIVATE_KEY,
GPG_PASSPHRASE) into vanniktech's expected ORG_GRADLE_PROJECT_*
environment variables."
```

---

## Final verification

- [ ] **Step 1: Make sure the working tree is clean and all six tasks committed**

Run: `git status` and `git log --oneline -7`

Expected: working tree clean; the six new commits visible in `git log`.

- [ ] **Step 2: Full build one more time**

Run: `./gradlew clean build --stacktrace`

Expected: `BUILD SUCCESSFUL`. This is the same check Task 5's CI workflow will run on every PR.

- [ ] **Step 3: Out-of-band manual setup (not part of this plan — checklist for the user)**

These steps need real Central Portal / GitHub Settings access and are intentionally not automated:

1. Verify the `community.flock.wirespec.bytecode` namespace is claimed on central.sonatype.com.
2. Generate a Central Portal user token (Account → Generate User Token) and store the two values as repo secrets `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`.
3. Generate a GPG keypair (or reuse an existing one) and upload the public key with `gpg --send-keys --keyserver keys.openpgp.org <keyid>`.
4. Store the ASCII-armored private key (`gpg --export-secret-keys --armor <keyid>`) as repo secret `GPG_PRIVATE_KEY`, and its passphrase as `GPG_PASSPHRASE`.
5. Cut a throwaway first release (tag `v0.0.1`) to exercise the pipeline end-to-end before publishing anything meaningful.
