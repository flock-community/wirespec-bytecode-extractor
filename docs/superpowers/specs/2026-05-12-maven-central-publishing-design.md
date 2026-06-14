# GitHub Actions → Maven Central publishing

**Status:** Design approved 2026-05-12.
**Scope:** Wire `extractor-core` and `extractor-maven-plugin` to publish to
Sonatype Central Portal from GitHub Actions, triggered by a published GitHub
Release. Add a separate CI workflow that builds and tests on PRs and pushes to
`main`.

## Goals

- A `git`-only release flow: publishing a GitHub Release (tag `vX.Y.Z`) results
  in `community.flock.wirespec.bytecode:wirespec-bytecode-extractor-core:X.Y.Z` and
  `:wirespec-bytecode-extractor-maven-plugin:X.Y.Z` on Maven Central within
  ~15 min, with no human action between the release click and the artifacts
  going live.
- Continuous validation: every PR and every push to `main` runs the full
  `./gradlew build` suite (extractor-core unit tests, plugin unit tests, Maven
  integration tests).
- POM metadata satisfies Maven Central validation rules (URL, license, SCM,
  developers, signed artifacts, javadoc + sources jars).
- Secrets handling uses the four GitHub secrets the user has already named:
  `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.

## Non-goals

- Publishing `:integration-tests`. It is a verification-only module; it stays
  unpublished and continues to consume the two artifacts from a build-local
  `it-repo`.
- Snapshot publishing. The Central Portal does not support snapshots, and the
  legacy `s01.oss.sonatype.org` snapshot endpoint is out of scope for this
  iteration.
- Release-notes generation, changelog tooling, version bumping commits. The
  release author writes the release notes manually in the GitHub Release UI;
  the workflow only consumes the tag.
- Sigstore / cosign / SLSA provenance. PGP signing only (Central Portal
  requirement).

## Approach summary

Use the [`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin, applied to the two publishable subprojects from the root build script.
It is the de-facto standard for Central Portal publishing from Gradle in 2026
and gives:

- Native Central Portal upload + auto-promotion via
  `publishAndReleaseToMavenCentral`.
- Automatic javadoc + sources jar generation.
- In-memory PGP signing driven by Gradle properties (which we provide via
  `ORG_GRADLE_PROJECT_*` environment variables in the workflow).
- Sensible POM defaults; we override coordinates and add shared metadata.

The existing local `itLocal` Maven repository declarations in each subproject
stay — `:integration-tests` still resolves the freshly built plugin from
`build/it-repo`, and the vanniktech plugin coexists with extra `repositories`
blocks.

## Distribution & coordinates

Unchanged from the current setup:

- **Group:** `community.flock.wirespec.bytecode`
- **Artifacts:**
  - `wirespec-bytecode-extractor-core`
  - `wirespec-bytecode-extractor-maven-plugin` (packaging `maven-plugin`)
- **Version source:** the release tag (e.g. `v0.1.0` → version `0.1.0`). The
  workflow strips the leading `v` and passes the result via `-Pversion=`.
  `gradle.properties` stays at `0.0.0-SNAPSHOT` as the dev default; no version
  bump commits.

## Files

### New

- `.github/workflows/ci.yml` — build + test on PRs and pushes to `main`.
- `.github/workflows/release.yml` — publish to Central Portal when a GitHub
  Release is published.
- `LICENSE` — Apache 2.0 text, top-level.

### Modified

- `build.gradle.kts` (root) — apply `com.vanniktech.maven.publish` to the two
  publishable subprojects via `subprojects { ... }`; declare shared POM
  metadata (URL, license, SCM, developer) once.
- `gradle/libs.versions.toml` — add the
  `com.vanniktech.maven.publish` plugin coordinate.
- `extractor-core/build.gradle.kts` — remove the local
  `publishing { publications { create("maven") { ... } } }` block (vanniktech
  generates the publication). Keep the `itLocal` repositories block.
- `extractor-maven-plugin/build.gradle.kts` — same removal; keep `itLocal`.
  The Maven-plugin-specific `packaging = "maven-plugin"` POM tweak moves into
  the root configure block under a `name == "extractor-maven-plugin"` branch.
- `gradle.properties` — add `SONATYPE_HOST=CENTRAL_PORTAL` and
  `RELEASE_SIGNING_ENABLED=true` (vanniktech conventions).

## Workflow 1 — `.github/workflows/ci.yml`

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

Notes:

- `./gradlew build` already runs every module's tests, including the Maven
  integration tests under `:integration-tests` (which publish locally via the
  retained `itLocal` repo — no Central Portal credentials required).
- `gradle/actions/setup-gradle@v4` enables Gradle's remote build cache and
  dependency cache transparently.

## Workflow 2 — `.github/workflows/release.yml`

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
          ORG_GRADLE_PROJECT_signingInMemoryKey:         ${{ secrets.GPG_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.GPG_PASSPHRASE }}
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          ./gradlew \
            -Pversion="$VERSION" \
            publishAndReleaseToMavenCentral --no-configuration-cache
```

Key points:

- **Trigger:** fires only when a Release is `published` (drafts do not count).
- **Checkout ref:** explicit `ref: ${{ github.event.release.tag_name }}`
  guarantees the build sees the exact tagged commit, even if `main` has
  advanced between the tag and the release being published.
- **Version override:** `-Pversion="$VERSION"` overrides the
  `0.0.0-SNAPSHOT` default in `gradle.properties` for the duration of this
  build only — no commit is required to bump versions.
- **Secret → Gradle property mapping:** Gradle reads
  `ORG_GRADLE_PROJECT_<name>` env vars as `-P<name>`. vanniktech keys off:
  - `mavenCentralUsername` / `mavenCentralPassword` — Central Portal user
    token (generated at central.sonatype.com → Account → Generate User Token).
  - `signingInMemoryKey` — the ASCII-armored private key block (output of
    `gpg --export-secret-keys --armor <keyid>`), passed as a single
    multi-line GitHub secret.
  - `signingInMemoryKeyPassword` — the key's passphrase.
- **`publishAndReleaseToMavenCentral`:** the vanniktech task that uploads
  every publication in the build to the Central Portal and then triggers the
  auto-publish promotion (matching the "Auto-publish" choice).
- **`--no-configuration-cache`:** the publish task is not
  configuration-cache-compatible yet.
- **`permissions: contents: read`:** the workflow does not push back to the
  repo; least-privilege token.

## Gradle changes — root `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

subprojects {
    if (name in setOf("extractor-core", "extractor-maven-plugin")) {
        apply(plugin = "com.vanniktech.maven.publish")

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()

            val publishedArtifactId = when (name) {
                "extractor-core"          -> "wirespec-bytecode-extractor-core"
                "extractor-maven-plugin"  -> "wirespec-bytecode-extractor-maven-plugin"
                else                      -> error("unreachable")
            }
            coordinates(
                groupId    = project.group.toString(),
                artifactId = publishedArtifactId,
                version    = project.version.toString(),
            )

            pom {
                name.set(publishedArtifactId)
                description.set(provider { project.description ?: publishedArtifactId })
                url.set("https://github.com/flock-community/wirespec-bytecode-extractor")
                if (name == "extractor-maven-plugin") {
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

## Gradle changes — `gradle/libs.versions.toml`

Add to `[versions]`:

```toml
vanniktech-maven-publish = "0.30.0"
```

Add to `[plugins]`:

```toml
vanniktech-maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktech-maven-publish" }
```

(`0.30.0` is the current 2026 release with stable Central Portal support; can
be bumped in the implementation step if a newer version exists.)

## Gradle changes — `gradle.properties`

Append:

```properties
SONATYPE_HOST=CENTRAL_PORTAL
RELEASE_SIGNING_ENABLED=true
```

The first tells vanniktech which Sonatype endpoint to use; the second is
required for `publishAndReleaseToMavenCentral` to actually sign artifacts
(vanniktech silently skips signing without it).

## Gradle changes — subproject build scripts

In both `extractor-core/build.gradle.kts` and
`extractor-maven-plugin/build.gradle.kts`:

- **Remove** the `publishing { publications { create<MavenPublication>("maven") { ... } } }`
  block. vanniktech generates this publication from the `java`/`maven-plugin`
  component.
- **Keep** the `publishing { repositories { maven { name = "itLocal"; url = ... } } }`
  block. `:integration-tests` still publishes to and resolves from this
  build-local repository; vanniktech does not interfere with extra
  repositories.
- **Remove** the per-module `pom { name; description; packaging }` content —
  the root `subprojects {}` block now owns POM metadata. (`packaging =
  "maven-plugin"` is handled in the root block under the
  `extractor-maven-plugin` branch.)

## GitHub repository secrets

The release workflow consumes exactly the four secrets the user named. These
must be set under **Settings → Secrets and variables → Actions** on the
`flock-community/wirespec-bytecode-extractor` repo:

| Secret | Value |
|---|---|
| `SONATYPE_USERNAME` | Central Portal user token name (from central.sonatype.com → Account → Generate User Token). |
| `SONATYPE_PASSWORD` | Central Portal user token password (paired with the name above). |
| `GPG_PRIVATE_KEY` | Full ASCII-armored block from `gpg --export-secret-keys --armor <keyid>` — including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` / `-----END ...-----` framing. Multi-line; GitHub stores it verbatim. |
| `GPG_PASSPHRASE` | Passphrase for that key. |

The matching **public key** must be uploaded to a keyserver Maven Central
trusts (`keys.openpgp.org`, `keyserver.ubuntu.com`, or `pgp.mit.edu`). Central
Portal validates the signature against the keyservers, not against the secret.

## Release procedure (after this lands)

1. `gradle.properties` stays at `version=0.0.0-SNAPSHOT` — no version-bump
   commits.
2. On GitHub: **Releases → Draft a new release → Choose a tag → `v0.1.0`
   (creating it on the target branch) → write release notes → Publish
   release**.
3. `release.yml` fires automatically. It checks out the tagged commit, builds,
   signs, uploads to the Portal, and triggers auto-promotion.
4. Within ~15 min, both artifacts appear under
   `https://repo1.maven.org/maven2/community/flock/wirespec/bytecode/`.

## Validation checklist (during implementation)

- [ ] `./gradlew build` is green locally before the workflow runs anywhere.
- [ ] `./gradlew publishToMavenLocal -Pversion=0.1.0-test` produces signed
      `.jar`, `-sources.jar`, `-javadoc.jar`, `.pom`, and `.asc` files for
      both modules under `~/.m2/repository/community/flock/wirespec/bytecode/`.
      (Requires local GPG setup; can also be tested with
      `signingInMemoryKey` env vars.)
- [ ] First release uses a clearly throwaway version (e.g. `v0.0.1`) so the
      whole pipeline is exercised before publishing anything meaningful.

## Risks & mitigations

- **GPG secret formatting.** The `GPG_PRIVATE_KEY` secret must include the
  literal `-----BEGIN/END PGP PRIVATE KEY BLOCK-----` markers and preserve
  newlines exactly. A common failure is pasting only the base64 body, which
  produces an "invalid key" error at publish time. Mitigation: documented
  explicitly in the secrets table above; first release will surface this if
  wrong.
- **Public key not on a trusted keyserver.** Central Portal will reject
  unsigned-by-known-key artifacts. Mitigation: include a "publish public key"
  step in the rollout checklist (`gpg --send-keys --keyserver keys.openpgp.org <keyid>`).
- **Namespace not yet claimed on Central Portal.** Publishing to a brand-new
  group requires DNS or GitHub-based namespace verification on the Portal
  side. Mitigation: confirm the `community.flock.wirespec.bytecode` namespace
  is claimed before the first release attempt. If it isn't, this is a one-time
  manual step on central.sonatype.com.
- **Auto-publish surface area.** With `automaticRelease = true` a bad upload
  promotes itself. Mitigation: the throwaway `v0.0.1` first release;
  validation rules on the Portal side still reject invalid POMs/signatures
  before promotion.
