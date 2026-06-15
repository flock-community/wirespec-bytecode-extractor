# wirespec-extractor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Maven plugin (Kotlin) that scans a Spring Boot application's compiled classes and emits Wirespec (`.ws`) files describing its HTTP endpoints and DTO types.

**Architecture:** A 5-stage pipeline (`ControllerScanner → EndpointExtractor → TypeExtractor → WirespecAstBuilder → Emitter`) driven by an `ExtractMojo`. Reflection on compiled classes (`target/classes`) plus the project's runtime classpath, scanned with ClassGraph. Emits `.ws` text via Wirespec's own `WirespecEmitter`.

**Tech Stack:**
- Kotlin 2.1.x, JVM 21
- Maven plugin: `maven-plugin-api` 3.9.x + `maven-plugin-annotations` 3.13.x
- Reflection scan: `io.github.classgraph:classgraph` 4.8.x
- Wirespec AST + emitter: `community.flock.wirespec.compiler:core-jvm` 0.17.20 + `community.flock.wirespec.compiler.emitters:wirespec-jvm` 0.17.20 (note: `emitters` is part of the groupId, not artifactId)
- Spring annotations (test-scope): `spring-web` 6.x, `spring-context` 6.x
- Tests: JUnit 5 + Kotest assertions
- Integration tests: `maven-invoker-plugin` 3.9.x

---

## File structure

After this plan is complete, the project will look like:

```
wirespec-extractor/
├── pom.xml
├── .gitignore
├── README.md
├── docs/superpowers/
│   ├── specs/2026-05-12-wirespec-extractor-design.md
│   └── plans/2026-05-12-wirespec-extractor.md
├── src/main/kotlin/community/flock/wirespec/extractor/
│   ├── ExtractMojo.kt                          # @Mojo entry point
│   ├── classpath/ClasspathBuilder.kt           # build URLClassLoader
│   ├── scan/ControllerScanner.kt               # find @RestController classes
│   ├── extract/
│   │   ├── EndpointExtractor.kt                # method → Endpoint
│   │   ├── ParamExtractor.kt                   # parameters → Param model
│   │   ├── ReturnTypeUnwrapper.kt              # ResponseEntity/Mono/Flux/Optional
│   │   ├── TypeExtractor.kt                    # Class/Type → WireType
│   │   ├── JacksonNames.kt                     # @JsonProperty / @JsonIgnore
│   │   ├── ValidationConstraints.kt            # Bean Validation annotations
│   │   └── NullabilityResolver.kt              # the priority chain
│   ├── ast/WirespecAstBuilder.kt               # internal model → Wirespec AST
│   ├── emit/Emitter.kt                         # Wirespec AST → .ws files
│   └── model/
│       ├── Endpoint.kt                         # internal model: endpoint
│       ├── Param.kt                            # internal model: parameter
│       └── WireType.kt                         # internal model: type
├── src/test/kotlin/community/flock/wirespec/extractor/
│   ├── classpath/ClasspathBuilderTest.kt
│   ├── scan/ControllerScannerTest.kt
│   ├── extract/
│   │   ├── EndpointExtractorTest.kt
│   │   ├── ParamExtractorTest.kt
│   │   ├── ReturnTypeUnwrapperTest.kt
│   │   ├── TypeExtractorTest.kt
│   │   ├── JacksonNamesTest.kt
│   │   ├── ValidationConstraintsTest.kt
│   │   └── NullabilityResolverTest.kt
│   ├── ast/WirespecAstBuilderTest.kt
│   ├── emit/EmitterTest.kt
│   └── fixtures/                               # synthetic test controllers + DTOs
│       ├── HelloController.kt
│       ├── UserController.kt
│       ├── dto/
│       │   ├── UserDto.kt
│       │   ├── CreateUserRequest.kt
│       │   └── Role.kt
│       └── wrapped/                            # ResponseEntity / Mono / Flux fixtures
│           └── WrappedController.kt
└── src/it/basic-spring-app/                    # maven-invoker integration test
    ├── pom.xml
    ├── invoker.properties
    ├── verify.groovy
    └── src/main/java/com/acme/api/
        ├── UserController.java
        ├── OrderController.java
        └── dto/...
```

---

## Task 1: Project setup — pom.xml and skeletal Mojo

**Files:**
- Create: `pom.xml`
- Create: `src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt`
- Create: `src/main/resources/META-INF/maven/extension.xml` (none needed — handled by maven-plugin-plugin)

- [ ] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>community.flock.wirespec.extractor</groupId>
    <artifactId>wirespec-extractor-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>maven-plugin</packaging>

    <name>Wirespec Extractor Maven Plugin</name>
    <description>Extracts Spring Boot endpoints into Wirespec .ws files.</description>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <kotlin.version>2.1.20</kotlin.version>
        <kotlin.compiler.jvmTarget>21</kotlin.compiler.jvmTarget>
        <maven.api.version>3.9.9</maven.api.version>
        <maven.plugin.tools.version>3.13.1</maven.plugin.tools.version>
        <wirespec.version>0.17.20</wirespec.version>
        <classgraph.version>4.8.179</classgraph.version>
        <spring.version>6.1.14</spring.version>
        <junit.version>5.11.3</junit.version>
        <kotest.version>5.9.1</kotest.version>
    </properties>

    <prerequisites>
        <maven>3.9.0</maven>
    </prerequisites>

    <dependencies>
        <!-- Maven plugin API -->
        <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-plugin-api</artifactId>
            <version>${maven.api.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-core</artifactId>
            <version>${maven.api.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.maven.plugin-tools</groupId>
            <artifactId>maven-plugin-annotations</artifactId>
            <version>${maven.plugin.tools.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Kotlin -->
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-stdlib</artifactId>
            <version>${kotlin.version}</version>
        </dependency>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-reflect</artifactId>
            <version>${kotlin.version}</version>
        </dependency>

        <!-- Classpath scanning -->
        <dependency>
            <groupId>io.github.classgraph</groupId>
            <artifactId>classgraph</artifactId>
            <version>${classgraph.version}</version>
        </dependency>

        <!-- Wirespec compiler + Wirespec-language emitter -->
        <dependency>
            <groupId>community.flock.wirespec.compiler</groupId>
            <artifactId>core-jvm</artifactId>
            <version>${wirespec.version}</version>
        </dependency>
        <dependency>
            <groupId>community.flock.wirespec.compiler.emitters</groupId>
            <artifactId>wirespec-jvm</artifactId>
            <version>${wirespec.version}</version>
        </dependency>

        <!-- Spring (compile scope — EndpointExtractor uses AnnotatedElementUtils
             at plugin runtime, and Maven plugins don't get a 'provided' classpath
             from the host project) -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
            <version>${spring.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
            <version>${spring.version}</version>
        </dependency>
        <!-- jackson-annotations is compile scope because JacksonNames (production code) imports it -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
            <version>2.18.1</version>
        </dependency>
        <!-- jakarta.validation-api is compile scope because ValidationConstraints (production code) imports it -->
        <dependency>
            <groupId>jakarta.validation</groupId>
            <artifactId>jakarta.validation-api</artifactId>
            <version>3.1.0</version>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId>
            <version>3.7.0</version>
            <scope>test</scope>
        </dependency>
        <!-- swagger-annotations is compile scope because NullabilityResolver imports @Schema -->
        <dependency>
            <groupId>io.swagger.core.v3</groupId>
            <artifactId>swagger-annotations</artifactId>
            <version>2.2.28</version>
        </dependency>

        <!-- Tests -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.kotest</groupId>
            <artifactId>kotest-assertions-core-jvm</artifactId>
            <version>${kotest.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <sourceDirectory>src/main/kotlin</sourceDirectory>
        <testSourceDirectory>src/test/kotlin</testSourceDirectory>

        <plugins>
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <version>${kotlin.version}</version>
                <executions>
                    <execution>
                        <id>compile</id>
                        <phase>process-sources</phase>
                        <goals><goal>compile</goal></goals>
                    </execution>
                    <execution>
                        <id>test-compile</id>
                        <phase>process-test-sources</phase>
                        <goals><goal>test-compile</goal></goals>
                    </execution>
                </executions>
                <configuration>
                    <jvmTarget>21</jvmTarget>
                    <args>
                        <!-- Required so Parameter.getName() returns real names instead of arg0/arg1.
                             Spring's @PathVariable/@RequestParam/etc. accept empty value() and fall
                             back to the parameter name; without this flag those fall back to "arg0". -->
                        <arg>-java-parameters</arg>
                    </args>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-plugin-plugin</artifactId>
                <version>${maven.plugin.tools.version}</version>
                <configuration>
                    <goalPrefix>wirespec</goalPrefix>
                </configuration>
                <executions>
                    <execution>
                        <id>default-descriptor</id>
                        <phase>process-classes</phase>
                    </execution>
                    <execution>
                        <id>help-goal</id>
                        <goals><goal>helpmojo</goal></goals>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Write the skeletal `ExtractMojo`**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt
package community.flock.wirespec.extractor

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File

@Mojo(
    name = "extract",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true,
)
class ExtractMojo : AbstractMojo() {

    @Parameter(required = true, property = "wirespec.output")
    lateinit var output: File

    @Parameter(property = "wirespec.basePackage")
    var basePackage: String? = null

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject

    override fun execute() {
        log.info("wirespec-extractor: output=$output basePackage=$basePackage")
        // Pipeline wired in Task 14.
    }
}
```

- [ ] **Step 3: Verify the build compiles and the plugin descriptor generates**

Run: `mvn -q clean install -DskipTests`
Expected: `BUILD SUCCESS`. Inspect `target/classes/META-INF/maven/plugin.xml` — should contain a `<mojo>` named `extract` with parameters `output` and `basePackage`.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt
git commit -m "Bootstrap Maven plugin skeleton with ExtractMojo

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Internal model types

We use a small internal data model so extractors can be unit-tested without the Wirespec AST.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/model/WireType.kt`
- Create: `src/main/kotlin/community/flock/wirespec/extractor/model/Param.kt`
- Create: `src/main/kotlin/community/flock/wirespec/extractor/model/Endpoint.kt`

- [ ] **Step 1: Write `WireType.kt`**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/model/WireType.kt
package community.flock.wirespec.extractor.model

/** Internal type model decoupled from the Wirespec AST. */
sealed interface WireType {
    val nullable: Boolean

    data class Primitive(val kind: Kind, override val nullable: Boolean = false) : WireType {
        enum class Kind { STRING, INTEGER_32, INTEGER_64, NUMBER_32, NUMBER_64, BOOLEAN, BYTES }
    }

    /** Reference to a named Object/Enum/Refined defined elsewhere. */
    data class Ref(val name: String, override val nullable: Boolean = false) : WireType

    data class ListOf(val element: WireType, override val nullable: Boolean = false) : WireType

    data class MapOf(val value: WireType, override val nullable: Boolean = false) : WireType

    /** Top-level definition: a class with named fields. */
    data class Object(
        val name: String,
        val fields: List<Field>,
        val description: String? = null,
        override val nullable: Boolean = false,
    ) : WireType

    /** Top-level definition: a finite set of string values. */
    data class EnumDef(
        val name: String,
        val values: List<String>,
        val description: String? = null,
        override val nullable: Boolean = false,
    ) : WireType

    /** Top-level definition: a refined String/Integer/Number with constraint. */
    data class Refined(
        val name: String,
        val base: Primitive,
        val regex: String? = null,
        val min: String? = null,
        val max: String? = null,
        override val nullable: Boolean = false,
    ) : WireType

    data class Field(
        val name: String,
        val type: WireType,
        val description: String? = null,
    )
}
```

- [ ] **Step 2: Write `Param.kt`**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/model/Param.kt
package community.flock.wirespec.extractor.model

data class Param(
    val name: String,
    val source: Source,
    val type: WireType,
) {
    enum class Source { PATH, QUERY, HEADER, COOKIE }
}
```

- [ ] **Step 3: Write `Endpoint.kt`**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/model/Endpoint.kt
package community.flock.wirespec.extractor.model

data class Endpoint(
    val controllerSimpleName: String,
    val name: String,                     // Wirespec definition name (PascalCase)
    val method: HttpMethod,
    val pathSegments: List<PathSegment>,
    val queryParams: List<Param>,
    val headerParams: List<Param>,
    val cookieParams: List<Param>,
    val requestBody: WireType? = null,
    val responseBody: WireType? = null,
    val statusCode: Int = 200,
) {
    enum class HttpMethod { GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD, TRACE }

    sealed interface PathSegment {
        data class Literal(val value: String) : PathSegment
        data class Variable(val name: String, val type: WireType) : PathSegment
    }
}
```

- [ ] **Step 4: Verify it compiles**

Run: `mvn -q compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/model/
git commit -m "Add internal model: WireType, Param, Endpoint

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: ClasspathBuilder

Build a `URLClassLoader` over the project's runtime classpath plus its compiled classes. Tested without a real `MavenProject` by passing a list of URLs.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilder.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilderTest.kt
package community.flock.wirespec.extractor.classpath

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class ClasspathBuilderTest {

    @Test
    fun `loader exposes urls in the order they were given`() {
        val a = File("/tmp/a.jar").toURI().toURL()
        val b = File("/tmp/b.jar").toURI().toURL()
        val parent = Thread.currentThread().contextClassLoader

        val loader = ClasspathBuilder.fromUrls(listOf(a, b), parent)

        loader.urLs.toList() shouldBe listOf(a, b)
    }

    @Test
    fun `loader can find a class on its classpath`() {
        val testClassesDir = File(
            ClasspathBuilderTest::class.java.protectionDomain.codeSource.location.toURI()
        )
        val parent = Thread.currentThread().contextClassLoader

        val loader = ClasspathBuilder.fromUrls(listOf(testClassesDir.toURI().toURL()), parent)

        val loaded = loader.loadClass(ClasspathBuilderTest::class.java.name)
        loaded.name shouldBe ClasspathBuilderTest::class.java.name
    }

    @Test
    fun `fromMavenInputs combines runtime classpath with output dir`() {
        val classesDir = File("/tmp/classes")
        val jar = File("/tmp/dep.jar")

        val urls = ClasspathBuilder.collectUrls(
            runtimeClasspathElements = listOf(jar.absolutePath),
            outputDirectory = classesDir,
        )

        urls shouldContain classesDir.toURI().toURL()
        urls shouldContain jar.toURI().toURL()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ClasspathBuilderTest`
Expected: COMPILE FAIL (`Unresolved reference: ClasspathBuilder`).

- [ ] **Step 3: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/classpath/ClasspathBuilder.kt
package community.flock.wirespec.extractor.classpath

import java.io.File
import java.net.URL
import java.net.URLClassLoader

object ClasspathBuilder {

    /** Build a child URLClassLoader over the given URLs. */
    fun fromUrls(urls: List<URL>, parent: ClassLoader): URLClassLoader =
        URLClassLoader(urls.toTypedArray(), parent)

    /**
     * Combine `outputDirectory` with the runtime classpath into the URL list
     * to feed a URLClassLoader. The output dir comes first so the project's
     * own classes win over duplicates pulled in transitively.
     */
    fun collectUrls(runtimeClasspathElements: List<String>, outputDirectory: File): List<URL> {
        val output = outputDirectory.toURI().toURL()
        val deps = runtimeClasspathElements.map { File(it).toURI().toURL() }
        return (listOf(output) + deps).distinct()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=ClasspathBuilderTest`
Expected: 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/classpath/ src/test/kotlin/community/flock/wirespec/extractor/classpath/
git commit -m "Add ClasspathBuilder

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: ControllerScanner

Scan a classloader (via ClassGraph) for classes annotated with `@RestController`, or `@Controller` with at least one `@ResponseBody`-bearing method. Apply package filters.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/scan/ControllerScanner.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/scan/ControllerScannerTest.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/HelloController.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/PlainController.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/MixedController.kt`

- [ ] **Step 1: Write fixtures**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/HelloController.kt
package community.flock.wirespec.extractor.fixtures

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/hello")
class HelloController {
    @GetMapping
    fun hello(): String = "hi"
}
```

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/PlainController.kt
package community.flock.wirespec.extractor.fixtures

import org.springframework.stereotype.Controller

@Controller
class PlainController {
    // No @ResponseBody anywhere — should be skipped.
    fun renderView() = "view"
}
```

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/MixedController.kt
package community.flock.wirespec.extractor.fixtures

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class MixedController {
    @GetMapping("/mixed")
    @ResponseBody
    fun api(): String = "data"
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/scan/ControllerScannerTest.kt
package community.flock.wirespec.extractor.scan

import community.flock.wirespec.extractor.fixtures.HelloController
import community.flock.wirespec.extractor.fixtures.MixedController
import community.flock.wirespec.extractor.fixtures.PlainController
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.junit.jupiter.api.Test

class ControllerScannerTest {

    private val loader = Thread.currentThread().contextClassLoader

    @Test
    fun `finds RestController classes`() {
        val found = ControllerScanner.scan(
            classLoader = loader,
            scanPackages = listOf("community.flock.wirespec.extractor.fixtures"),
            basePackage = null,
        ).map { it.name }

        found shouldContain HelloController::class.java.name
    }

    @Test
    fun `finds Controller classes that have ResponseBody handler methods`() {
        val found = ControllerScanner.scan(
            classLoader = loader,
            scanPackages = listOf("community.flock.wirespec.extractor.fixtures"),
            basePackage = null,
        ).map { it.name }

        found shouldContain MixedController::class.java.name
    }

    @Test
    fun `skips Controller classes without any ResponseBody methods`() {
        val found = ControllerScanner.scan(
            classLoader = loader,
            scanPackages = listOf("community.flock.wirespec.extractor.fixtures"),
            basePackage = null,
        ).map { it.name }

        found shouldNotContain PlainController::class.java.name
    }

    @Test
    fun `excludes framework packages by default`() {
        val found = ControllerScanner.scan(
            classLoader = loader,
            scanPackages = listOf("org.springframework"),
            basePackage = null,
        )

        found.forEach { c ->
            assert(!c.name.startsWith("org.springframework.")) {
                "Framework class leaked through scan: ${c.name}"
            }
        }
    }

    @Test
    fun `basePackage filter restricts to user code`() {
        val found = ControllerScanner.scan(
            classLoader = loader,
            scanPackages = listOf("community.flock.wirespec.extractor.fixtures"),
            basePackage = "community.flock.wirespec.extractor.fixtures",
        ).map { it.name }

        found shouldContain HelloController::class.java.name
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest=ControllerScannerTest`
Expected: COMPILE FAIL (`Unresolved reference: ControllerScanner`).

- [ ] **Step 4: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/scan/ControllerScanner.kt
package community.flock.wirespec.extractor.scan

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo

object ControllerScanner {

    private const val REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"
    private const val CONTROLLER = "org.springframework.stereotype.Controller"
    private const val RESPONSE_BODY = "org.springframework.web.bind.annotation.ResponseBody"

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "springfox",
        "io.swagger",
        "org.apache",
    )

    /**
     * Scan [classLoader] for Spring controllers.
     *
     * @param scanPackages packages to include in the scan; pass empty for "everything reachable".
     * @param basePackage  if non-null, additionally restrict results to classes whose FQN starts with this prefix.
     */
    fun scan(
        classLoader: ClassLoader,
        scanPackages: List<String>,
        basePackage: String?,
    ): List<Class<*>> {
        val graph = ClassGraph()
            .overrideClassLoaders(classLoader)
            .ignoreParentClassLoaders()
            .enableClassInfo()
            .enableAnnotationInfo()
            .enableMethodInfo()  // required for hasResponseBodyMethod() — ClassGraph throws without it

        val accepted = scanPackages.filter { it.isNotBlank() }
        if (accepted.isNotEmpty()) graph.acceptPackages(*accepted.toTypedArray())

        graph.scan().use { result ->
            val direct = result.getClassesWithAnnotation(REST_CONTROLLER)
            val viaController = result.getClassesWithAnnotation(CONTROLLER)
                .filter { hasResponseBodyMethod(it) }

            return (direct + viaController)
                .distinctBy { it.name }
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
                .map { ci -> ci.loadClass() }
        }
    }

    private fun hasResponseBodyMethod(ci: ClassInfo): Boolean =
        ci.methodInfo.any { m -> m.hasAnnotation(RESPONSE_BODY) }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -q test -Dtest=ControllerScannerTest`
Expected: 5 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/scan/ src/test/kotlin/community/flock/wirespec/extractor/scan/ src/test/kotlin/community/flock/wirespec/extractor/fixtures/
git commit -m "Add ControllerScanner

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: EndpointExtractor — basic mapping resolution

Given a Class, produce a list of Endpoint records resolving combined class+method `@RequestMapping` paths and HTTP methods. Multi-method mappings produce one Endpoint per method.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractor.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractorTest.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/MultiMappingController.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/InheritingController.kt`

- [ ] **Step 1: Write fixtures**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/MultiMappingController.kt
package community.flock.wirespec.extractor.fixtures

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/multi")
class MultiMappingController {
    @RequestMapping(method = [RequestMethod.GET, RequestMethod.HEAD])
    fun both(): String = "x"
}
```

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/InheritingController.kt
package community.flock.wirespec.extractor.fixtures

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/parent")
abstract class ParentController {
    @GetMapping("/child")
    abstract fun handler(): String
}

@RestController
class InheritingController : ParentController() {
    override fun handler(): String = "ok"
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractorTest.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.fixtures.HelloController
import community.flock.wirespec.extractor.fixtures.InheritingController
import community.flock.wirespec.extractor.fixtures.MultiMappingController
import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EndpointExtractorTest {

    @Test
    fun `combines class-level and method-level paths`() {
        val endpoints = EndpointExtractor.extract(HelloController::class.java)

        endpoints shouldHaveSize 1
        val ep = endpoints.single()
        ep.method shouldBe HttpMethod.GET
        ep.pathSegments shouldBe listOf(PathSegment.Literal("hello"))
        ep.controllerSimpleName shouldBe "HelloController"
    }

    @Test
    fun `multi-method mapping produces one endpoint per method`() {
        val methods = EndpointExtractor.extract(MultiMappingController::class.java).map { it.method }

        methods shouldContain HttpMethod.GET
        methods shouldContain HttpMethod.HEAD
        methods shouldHaveSize 2
    }

    @Test
    fun `honors inherited @RequestMapping from a superclass`() {
        val endpoints = EndpointExtractor.extract(InheritingController::class.java)

        endpoints shouldHaveSize 1
        endpoints.single().pathSegments shouldBe listOf(
            PathSegment.Literal("parent"),
            PathSegment.Literal("child"),
        )
    }

    @Test
    fun `endpoint name is PascalCase of method name`() {
        val ep = EndpointExtractor.extract(HelloController::class.java).single()
        ep.name shouldBe "Hello"
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest=EndpointExtractorTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractor.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import java.lang.reflect.Method

object EndpointExtractor {

    /**
     * Extract all Wirespec endpoints from one Spring controller class.
     * Parameter, body and return-type extraction are stubs in this task —
     * they're filled in by Tasks 6 and 7.
     */
    fun extract(controllerClass: Class<*>): List<Endpoint> {
        val classMapping = AnnotatedElementUtils.findMergedAnnotation(controllerClass, RequestMapping::class.java)
        val classPaths = classMapping?.path?.toList()?.takeIf { it.isNotEmpty() } ?: listOf("")

        return controllerClass.methods.flatMap { method ->
            extractFromMethod(controllerClass, classPaths, method)
        }
    }

    private fun extractFromMethod(
        controllerClass: Class<*>,
        classPaths: List<String>,
        method: Method,
    ): List<Endpoint> {
        val mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping::class.java)
            ?: return emptyList()

        val methodPaths = mapping.path.toList().takeIf { it.isNotEmpty() } ?: listOf("")
        val httpMethods = if (mapping.method.isEmpty()) listOf(RequestMethod.GET) else mapping.method.toList()

        return httpMethods.flatMap { rm ->
            classPaths.flatMap { cp ->
                methodPaths.map { mp ->
                    Endpoint(
                        controllerSimpleName = controllerClass.simpleName,
                        name = pascalCase(method.name),
                        method = rm.toHttpMethod(),
                        pathSegments = parsePath(joinPath(cp, mp)),
                        queryParams = emptyList(),    // Task 6
                        headerParams = emptyList(),   // Task 6
                        cookieParams = emptyList(),   // Task 6
                        requestBody = null,           // Task 6
                        responseBody = null,          // Task 7
                        statusCode = 200,             // Task 7
                    )
                }
            }
        }
    }

    private fun joinPath(a: String, b: String): String {
        val left = a.trim('/').takeIf { it.isNotBlank() }
        val right = b.trim('/').takeIf { it.isNotBlank() }
        return listOfNotNull(left, right).joinToString("/")
    }

    internal fun parsePath(path: String): List<PathSegment> =
        path.split('/').filter { it.isNotBlank() }.map { seg ->
            val match = Regex("""^\{([^:}]+)(?::[^}]+)?}$""").matchEntire(seg)
            if (match != null) {
                PathSegment.Variable(
                    name = match.groupValues[1],
                    type = community.flock.wirespec.extractor.model.WireType.Primitive(
                        community.flock.wirespec.extractor.model.WireType.Primitive.Kind.STRING,
                    ),
                )
            } else {
                PathSegment.Literal(seg)
            }
        }

    internal fun pascalCase(name: String): String =
        if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)

    private fun RequestMethod.toHttpMethod(): HttpMethod = when (this) {
        RequestMethod.GET -> HttpMethod.GET
        RequestMethod.POST -> HttpMethod.POST
        RequestMethod.PUT -> HttpMethod.PUT
        RequestMethod.PATCH -> HttpMethod.PATCH
        RequestMethod.DELETE -> HttpMethod.DELETE
        RequestMethod.OPTIONS -> HttpMethod.OPTIONS
        RequestMethod.HEAD -> HttpMethod.HEAD
        RequestMethod.TRACE -> HttpMethod.TRACE
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -q test -Dtest=EndpointExtractorTest`
Expected: 4 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractor.kt src/test/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractorTest.kt src/test/kotlin/community/flock/wirespec/extractor/fixtures/MultiMappingController.kt src/test/kotlin/community/flock/wirespec/extractor/fixtures/InheritingController.kt
git commit -m "Add EndpointExtractor: combined paths + multi-method support

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: ParamExtractor — path/query/header/cookie params + request body

We extract this into its own file (`ParamExtractor`) so `EndpointExtractor` stays focused on mapping resolution. Note: full type extraction is Task 8 — for this task, params and bodies are typed as `WireType.Primitive(STRING)` (for params) or `WireType.Ref("Unknown")` (for bodies). Task 8 will swap in the real `TypeExtractor`.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/extract/ParamExtractor.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/extract/ParamExtractorTest.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/ParamsController.kt`
- Modify: `src/main/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractor.kt` — call `ParamExtractor` instead of returning empty lists.

- [ ] **Step 1: Write the fixture**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/ParamsController.kt
package community.flock.wirespec.extractor.fixtures

import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ParamsController {

    @GetMapping("/items/{id}")
    fun getItem(
        @PathVariable id: String,
        @RequestParam q: String,
        @RequestParam(required = false) page: Int?,
        @RequestHeader("X-Trace") trace: String,
        @CookieValue("session") session: String,
    ): String = ""

    @PostMapping("/items")
    fun postItem(@RequestBody body: String): String = ""
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/extract/ParamExtractorTest.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.fixtures.ParamsController
import community.flock.wirespec.extractor.model.Param.Source
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ParamExtractorTest {

    private val getItem = ParamsController::class.java.getDeclaredMethod(
        "getItem", String::class.java, String::class.java, Integer::class.java,
        String::class.java, String::class.java,
    )

    private val postItem = ParamsController::class.java.getDeclaredMethod("postItem", String::class.java)

    @Test
    fun `path variables are PATH params named after the variable`() {
        val params = ParamExtractor.extractParams(getItem)
        val pathParams = params.filter { it.source == Source.PATH }
        pathParams shouldHaveSize 1
        pathParams.single().name shouldBe "id"
    }

    @Test
    fun `request params are QUERY params`() {
        val params = ParamExtractor.extractParams(getItem)
        params.filter { it.source == Source.QUERY }.map { it.name } shouldBe listOf("q", "page")
    }

    @Test
    fun `request headers are HEADER params named by their value`() {
        val params = ParamExtractor.extractParams(getItem)
        val headers = params.filter { it.source == Source.HEADER }
        headers shouldHaveSize 1
        headers.single().name shouldBe "X-Trace"
    }

    @Test
    fun `cookie values are COOKIE params named by their value`() {
        val params = ParamExtractor.extractParams(getItem)
        val cookies = params.filter { it.source == Source.COOKIE }
        cookies shouldHaveSize 1
        cookies.single().name shouldBe "session"
    }

    @Test
    fun `request body parameter is detected and is not a Param`() {
        ParamExtractor.extractRequestBodyParameter(postItem) shouldBe postItem.parameters.first()
        ParamExtractor.extractParams(postItem) shouldBe emptyList()
    }

    @Test
    fun `getItem has no @RequestBody parameter`() {
        ParamExtractor.extractRequestBodyParameter(getItem) shouldBe null
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest=ParamExtractorTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/extract/ParamExtractor.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.Param
import community.flock.wirespec.extractor.model.Param.Source
import community.flock.wirespec.extractor.model.WireType
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import java.lang.reflect.Method
import java.lang.reflect.Parameter

object ParamExtractor {

    /** Extract all non-body parameters of [method]. */
    fun extractParams(method: Method): List<Param> =
        method.parameters.mapNotNull(::toParam)

    /** Find the first @RequestBody parameter, if any. */
    fun extractRequestBodyParameter(method: Method): Parameter? =
        method.parameters.firstOrNull { it.isAnnotationPresent(RequestBody::class.java) }

    private fun toParam(p: Parameter): Param? {
        p.getAnnotation(PathVariable::class.java)?.let { a ->
            return Param(name = a.value.ifEmpty { p.name }, source = Source.PATH, type = stringPlaceholder())
        }
        p.getAnnotation(RequestParam::class.java)?.let { a ->
            return Param(name = a.value.ifEmpty { p.name }, source = Source.QUERY, type = stringPlaceholder())
        }
        p.getAnnotation(RequestHeader::class.java)?.let { a ->
            return Param(name = a.value.ifEmpty { p.name }, source = Source.HEADER, type = stringPlaceholder())
        }
        p.getAnnotation(CookieValue::class.java)?.let { a ->
            return Param(name = a.value.ifEmpty { p.name }, source = Source.COOKIE, type = stringPlaceholder())
        }
        return null  // unannotated parameters and @RequestBody are skipped here
    }

    /**
     * Placeholder type. Task 8 (TypeExtractor) replaces this with real type
     * resolution by passing in a TypeExtractor instance.
     */
    private fun stringPlaceholder() = WireType.Primitive(WireType.Primitive.Kind.STRING)
}
```

- [ ] **Step 5: Wire `ParamExtractor` into `EndpointExtractor`**

Edit `EndpointExtractor.extractFromMethod` — replace the four empty `*Params = emptyList()` lines and `requestBody = null` with the call below. Show only the changed `Endpoint(...)` construction:

```kotlin
val allParams = ParamExtractor.extractParams(method)
val bodyParam = ParamExtractor.extractRequestBodyParameter(method)

Endpoint(
    controllerSimpleName = controllerClass.simpleName,
    name = pascalCase(method.name),
    method = rm.toHttpMethod(),
    pathSegments = parsePath(joinPath(cp, mp)),
    queryParams = allParams.filter { it.source == community.flock.wirespec.extractor.model.Param.Source.QUERY },
    headerParams = allParams.filter { it.source == community.flock.wirespec.extractor.model.Param.Source.HEADER },
    cookieParams = allParams.filter { it.source == community.flock.wirespec.extractor.model.Param.Source.COOKIE },
    requestBody = bodyParam?.let { _ ->
        // Real type resolution comes in Task 8.
        community.flock.wirespec.extractor.model.WireType.Ref("Unknown")
    },
    responseBody = null,
    statusCode = 200,
)
```

- [ ] **Step 6: Add an EndpointExtractor test that exercises params + body**

Append to `EndpointExtractorTest.kt`:

```kotlin
@Test
fun `params and body propagate from ParamExtractor`() {
    val ep = community.flock.wirespec.extractor.extract.EndpointExtractor
        .extract(community.flock.wirespec.extractor.fixtures.ParamsController::class.java)
        .single { it.name == "PostItem" }
    ep.requestBody shouldBe community.flock.wirespec.extractor.model.WireType.Ref("Unknown")
}
```

(`shouldBe` is already imported.)

- [ ] **Step 7: Run all extract tests**

Run: `mvn -q test -Dtest='ParamExtractorTest,EndpointExtractorTest'`
Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/extract/ src/test/kotlin/community/flock/wirespec/extractor/extract/ParamExtractorTest.kt src/test/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractorTest.kt src/test/kotlin/community/flock/wirespec/extractor/fixtures/ParamsController.kt
git commit -m "Add ParamExtractor; wire into EndpointExtractor

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: ReturnTypeUnwrapper — wrap-type unwrapping + status code

Resolve a method's effective response type by unwrapping `ResponseEntity<T>`, `Mono<T>`, `Flux<T>` (→ `List<T>`), `Optional<T>`, `Callable<T>`, `DeferredResult<T>`. Determine status code from `@ResponseStatus` or default 200/204.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/extract/ReturnTypeUnwrapper.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/extract/ReturnTypeUnwrapperTest.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/wrapped/WrappedController.kt`
- Modify: `src/main/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractor.kt` — wire `responseBody` and `statusCode`.

- [ ] **Step 1: Write the fixture**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/wrapped/WrappedController.kt
package community.flock.wirespec.extractor.fixtures.wrapped

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Optional
import java.util.concurrent.Callable

data class Item(val id: String)

@RestController
class WrappedController {

    @GetMapping("/raw")        fun raw(): Item = Item("x")
    @GetMapping("/entity")     fun entity(): ResponseEntity<Item> = ResponseEntity.ok(Item("x"))
    @GetMapping("/optional")   fun opt(): Optional<Item> = Optional.empty()
    @GetMapping("/mono")       fun mono(): Mono<Item> = Mono.empty()
    @GetMapping("/flux")       fun flux(): Flux<Item> = Flux.empty()
    @GetMapping("/callable")   fun callable(): Callable<Item> = Callable { Item("x") }
    @GetMapping("/void")       fun voided() {}
    @GetMapping("/monovoid")   fun monoVoid(): Mono<Void> = Mono.empty()

    @PostMapping("/created")
    @ResponseStatus(HttpStatus.CREATED)
    fun created(): Item = Item("x")
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/extract/ReturnTypeUnwrapperTest.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.fixtures.wrapped.Item
import community.flock.wirespec.extractor.fixtures.wrapped.WrappedController
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.lang.reflect.ParameterizedType

class ReturnTypeUnwrapperTest {

    private fun method(name: String) = WrappedController::class.java.declaredMethods.first { it.name == name }

    @Test
    fun `raw type returns the type itself`() {
        ReturnTypeUnwrapper.unwrap(method("raw").genericReturnType) shouldBe ReturnTypeUnwrapper.Unwrapped(Item::class.java, isList = false, isVoid = false)
    }

    @Test
    fun `ResponseEntity unwraps to inner type`() {
        val out = ReturnTypeUnwrapper.unwrap(method("entity").genericReturnType)
        out shouldBe ReturnTypeUnwrapper.Unwrapped(Item::class.java, isList = false, isVoid = false)
    }

    @Test
    fun `Optional unwraps to inner type`() {
        ReturnTypeUnwrapper.unwrap(method("opt").genericReturnType).type shouldBe Item::class.java
    }

    @Test
    fun `Mono unwraps to inner type`() {
        ReturnTypeUnwrapper.unwrap(method("mono").genericReturnType).type shouldBe Item::class.java
    }

    @Test
    fun `Flux unwraps to inner type with isList=true`() {
        val out = ReturnTypeUnwrapper.unwrap(method("flux").genericReturnType)
        out.type shouldBe Item::class.java
        out.isList shouldBe true
    }

    @Test
    fun `Callable unwraps to inner type`() {
        ReturnTypeUnwrapper.unwrap(method("callable").genericReturnType).type shouldBe Item::class.java
    }

    @Test
    fun `void method has isVoid=true and default status 204`() {
        val out = ReturnTypeUnwrapper.unwrap(method("voided").genericReturnType)
        out.isVoid shouldBe true
        ReturnTypeUnwrapper.statusCodeFor(method("voided"), out) shouldBe 204
    }

    @Test
    fun `Mono Void has isVoid=true and default status 204`() {
        val out = ReturnTypeUnwrapper.unwrap(method("monoVoid").genericReturnType)
        out.isVoid shouldBe true
        ReturnTypeUnwrapper.statusCodeFor(method("monoVoid"), out) shouldBe 204
    }

    @Test
    fun `default status for value-returning method is 200`() {
        val out = ReturnTypeUnwrapper.unwrap(method("raw").genericReturnType)
        ReturnTypeUnwrapper.statusCodeFor(method("raw"), out) shouldBe 200
    }

    @Test
    fun `@ResponseStatus overrides the default`() {
        val m = method("created")
        ReturnTypeUnwrapper.statusCodeFor(m, ReturnTypeUnwrapper.unwrap(m.genericReturnType)) shouldBe 201
    }

    @Test
    fun `nested wrapper ResponseEntity Mono Item unwraps recursively`() {
        // Smoke check via a synthetic ParameterizedType using Flux<Item> as a stand-in.
        val t = method("flux").genericReturnType.shouldBeInstanceOf<ParameterizedType>()
        ReturnTypeUnwrapper.unwrap(t).type shouldBe Item::class.java
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest=ReturnTypeUnwrapperTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/extract/ReturnTypeUnwrapper.kt
package community.flock.wirespec.extractor.extract

import org.springframework.web.bind.annotation.ResponseStatus
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

object ReturnTypeUnwrapper {

    private val WRAPPERS = setOf(
        "org.springframework.http.ResponseEntity",
        "java.util.Optional",
        "reactor.core.publisher.Mono",
        "java.util.concurrent.Callable",
        "org.springframework.web.context.request.async.DeferredResult",
    )
    private const val FLUX = "reactor.core.publisher.Flux"

    /** A flattened view of a method's effective response payload. */
    data class Unwrapped(val type: Type, val isList: Boolean, val isVoid: Boolean)

    fun unwrap(returnType: Type): Unwrapped {
        var current = returnType
        var isList = false

        while (true) {
            val rawName = (current as? ParameterizedType)?.rawType?.typeName
                ?: (current as? Class<*>)?.name

            if (rawName == FLUX && current is ParameterizedType) {
                isList = true
                current = current.actualTypeArguments[0]
                continue
            }
            if (rawName in WRAPPERS && current is ParameterizedType) {
                current = current.actualTypeArguments[0]
                continue
            }
            break
        }

        val isVoid = when (current) {
            is Class<*> -> current == Void.TYPE || current == Void::class.java
            else        -> current.typeName == "java.lang.Void"
        }

        return Unwrapped(current, isList = isList, isVoid = isVoid)
    }

    fun statusCodeFor(method: Method, unwrapped: Unwrapped): Int {
        val rs = method.getAnnotation(ResponseStatus::class.java)
        if (rs != null) {
            val codeAttr = rs.code
            val valueAttr = rs.value
            // Spring treats the two as aliases; pick whichever isn't the default.
            val code = if (codeAttr.value() != 500) codeAttr else valueAttr
            return code.value()
        }
        return if (unwrapped.isVoid) 204 else 200
    }
}
```

- [ ] **Step 5: Wire into `EndpointExtractor`**

Replace the `responseBody = null,` and `statusCode = 200,` lines in `EndpointExtractor.extractFromMethod` with:

```kotlin
val unwrapped = ReturnTypeUnwrapper.unwrap(method.genericReturnType)
val responseRef = if (unwrapped.isVoid) null
    else community.flock.wirespec.extractor.model.WireType.Ref(
        (unwrapped.type as? Class<*>)?.simpleName ?: "Unknown"
    ).let { ref ->
        if (unwrapped.isList) community.flock.wirespec.extractor.model.WireType.ListOf(ref)
        else ref
    }

Endpoint(
    /* ... unchanged fields ... */
    responseBody = responseRef,
    statusCode = ReturnTypeUnwrapper.statusCodeFor(method, unwrapped),
)
```

- [ ] **Step 6: Run all tests**

Run: `mvn -q test`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/extract/ src/test/kotlin/community/flock/wirespec/extractor/extract/ReturnTypeUnwrapperTest.kt src/test/kotlin/community/flock/wirespec/extractor/fixtures/wrapped/
git commit -m "Add ReturnTypeUnwrapper: wrappers + status code

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: TypeExtractor — basic field walk + primitives + collections + generics

This is the foundation type walker. Layers (Jackson, Validation, springdoc, nullability) are stacked on top in Tasks 9-12.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/UserDto.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/Role.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/Container.kt`

- [ ] **Step 1: Write fixtures**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/UserDto.kt
package community.flock.wirespec.extractor.fixtures.dto

data class UserDto(
    val id: String,
    val age: Int,
    val active: Boolean,
    val role: Role,
    val tags: List<String>,
)
```

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/Role.kt
package community.flock.wirespec.extractor.fixtures.dto

enum class Role { ADMIN, MEMBER }
```

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/Container.kt
package community.flock.wirespec.extractor.fixtures.dto

class Container<T>(val items: List<T>, val first: T)
```

- [ ] **Step 2: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.fixtures.dto.Container
import community.flock.wirespec.extractor.fixtures.dto.Role
import community.flock.wirespec.extractor.fixtures.dto.UserDto
import community.flock.wirespec.extractor.model.WireType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class TypeExtractorTest {

    private val extractor = TypeExtractor()

    @Test
    fun `String maps to STRING primitive`() {
        extractor.extract(String::class.java) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `Int maps to INTEGER_32 primitive`() {
        extractor.extract(Int::class.javaPrimitiveType!!) shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
    }

    @Test
    fun `Long maps to INTEGER_64 primitive`() {
        extractor.extract(Long::class.javaPrimitiveType!!) shouldBe WireType.Primitive(WireType.Primitive.Kind.INTEGER_64)
    }

    @Test
    fun `Boolean maps to BOOLEAN primitive`() {
        extractor.extract(Boolean::class.javaPrimitiveType!!) shouldBe WireType.Primitive(WireType.Primitive.Kind.BOOLEAN)
    }

    @Test
    fun `enum becomes Ref to its simple name and is registered as EnumDef`() {
        val ref = extractor.extract(Role::class.java)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "Role"

        val def = extractor.definitions.single { (it as? WireType.EnumDef)?.name == "Role" } as WireType.EnumDef
        def.values shouldBe listOf("ADMIN", "MEMBER")
    }

    @Test
    fun `class becomes Ref to its simple name and is registered as Object`() {
        val ref = extractor.extract(UserDto::class.java)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "UserDto"

        val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "UserDto" } as WireType.Object
        obj.fields.map { it.name } shouldBe listOf("id", "age", "active", "role", "tags")
        obj.fields.first { it.name == "tags" }.type.shouldBeInstanceOf<WireType.ListOf>()
    }

    @Test
    fun `parameterized List of String becomes ListOf STRING primitive`() {
        val type = UserDto::class.java.getDeclaredField("tags").genericType
        val out = extractor.extract(type)
        out.shouldBeInstanceOf<WireType.ListOf>().element shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `unbound generic ParameterizedType resolves to ListOf STRING with warning`() {
        val type = Container::class.java.getDeclaredField("items").genericType
        val out = extractor.extract(type)
        out.shouldBeInstanceOf<WireType.ListOf>().element shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }

    @Test
    fun `recursive structure does not stack overflow (cycle cache)`() {
        // Self-referential class via reflection stub.
        val ref = extractor.extract(SelfRef::class.java)
        ref.shouldBeInstanceOf<WireType.Ref>().name shouldBe "SelfRef"
    }

    data class SelfRef(val next: SelfRef?)
}
```

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest=TypeExtractorTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.WireType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.UUID

/**
 * Walks Java/Kotlin types and produces internal WireType references plus
 * a registered set of top-level definitions (Object/Enum/Refined).
 *
 * One TypeExtractor instance is used per scan run so the `definitions` set
 * accumulates across all controllers.
 */
class TypeExtractor {

    private val cache = mutableMapOf<String, WireType>()
    private val _definitions = linkedSetOf<WireType>()

    val definitions: Set<WireType> get() = _definitions

    /** Extract a [WireType] for [type]. Top-level Object/Enum/Refined definitions accumulate in [definitions]. */
    fun extract(type: Type): WireType = extractInner(type, nullable = false)

    private fun extractInner(type: Type, nullable: Boolean): WireType = when (type) {
        is Class<*>          -> fromClass(type, nullable)
        is ParameterizedType -> fromParameterized(type, nullable)
        is WildcardType      -> extractInner(type.upperBounds.firstOrNull() ?: Any::class.java, nullable)
        is TypeVariable<*>   -> WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
        else                 -> WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
    }

    private fun fromClass(cls: Class<*>, nullable: Boolean): WireType {
        primitiveOf(cls)?.let { return it.copy(nullable = nullable) }
        if (cls == String::class.java) return WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
        if (cls == ByteArray::class.java) return WireType.Primitive(WireType.Primitive.Kind.BYTES, nullable)
        if (cls == UUID::class.java) return WireType.Primitive(WireType.Primitive.Kind.STRING, nullable)
        if (Enum::class.java.isAssignableFrom(cls)) {
            cache[cls.name]?.let { return (it as WireType.Ref).copy(nullable = nullable) }
            val ref = WireType.Ref(cls.simpleName, nullable)
            cache[cls.name] = ref.copy(nullable = false)
            @Suppress("UNCHECKED_CAST")
            val values = (cls.enumConstants as Array<Enum<*>>).map { it.name }
            _definitions += WireType.EnumDef(cls.simpleName, values)
            return ref
        }
        if (Collection::class.java.isAssignableFrom(cls)) {
            return WireType.ListOf(WireType.Primitive(WireType.Primitive.Kind.STRING), nullable)
        }
        // Object class — register and recurse into its fields.
        cache[cls.name]?.let { return (it as WireType.Ref).copy(nullable = nullable) }
        val ref = WireType.Ref(cls.simpleName, nullable)
        cache[cls.name] = ref.copy(nullable = false)
        val fields = walkFields(cls)
        _definitions += WireType.Object(cls.simpleName, fields)
        return ref
    }

    private fun fromParameterized(pt: ParameterizedType, nullable: Boolean): WireType {
        val raw = pt.rawType as Class<*>
        if (Collection::class.java.isAssignableFrom(raw)) {
            val arg = pt.actualTypeArguments[0]
            val element = if (arg is WildcardType || arg is TypeVariable<*>)
                WireType.Primitive(WireType.Primitive.Kind.STRING)
            else
                extractInner(arg, nullable = false)
            return WireType.ListOf(element, nullable)
        }
        if (Map::class.java.isAssignableFrom(raw)) {
            val valueArg = pt.actualTypeArguments[1]
            val v = extractInner(valueArg, nullable = false)
            return WireType.MapOf(v, nullable)
        }
        // Generic value class — fall back to its raw type for now.
        return fromClass(raw, nullable)
    }

    /** Override-able by subclasses (Tasks 9-12) to inject Jackson/Validation/Schema processing. */
    protected open fun walkFields(cls: Class<*>): List<WireType.Field> =
        propertyMembers(cls).map { (name, type) ->
            WireType.Field(name = name, type = extractInner(type, nullable = false))
        }

    /**
     * Discover (name, generic-type) pairs for a class:
     * 1. Java records: record components.
     * 2. Kotlin data classes: declared fields (Kotlin generates fields for properties).
     * 3. JavaBeans: getters paired with backing fields.
     */
    protected fun propertyMembers(cls: Class<*>): List<Pair<String, Type>> {
        if (cls.isRecord) {
            return cls.recordComponents.map { it.name to it.genericType }
        }
        // Kotlin data classes / POJOs: prefer declared non-static, non-synthetic fields.
        val declared = cls.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name to it.genericType }
        if (declared.isNotEmpty()) return declared
        // Pure JavaBean: getXxx / isXxx pairs.
        return cls.methods
            .filter { it.parameterCount == 0 && (it.name.startsWith("get") || it.name.startsWith("is")) && it.declaringClass != Any::class.java }
            .filter { it.name != "getClass" }
            .map { method ->
                val raw = method.name.removePrefix("get").removePrefix("is")
                val name = raw.replaceFirstChar { it.lowercase() }
                name to method.genericReturnType
            }
    }

    private fun primitiveOf(cls: Class<*>): WireType.Primitive? = when (cls) {
        Int::class.javaPrimitiveType, Integer::class.java        -> WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> WireType.Primitive(WireType.Primitive.Kind.INTEGER_64)
        Short::class.javaPrimitiveType, java.lang.Short::class.java -> WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
        Byte::class.javaPrimitiveType, java.lang.Byte::class.java   -> WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> WireType.Primitive(WireType.Primitive.Kind.NUMBER_32)
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> WireType.Primitive(WireType.Primitive.Kind.NUMBER_64)
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> WireType.Primitive(WireType.Primitive.Kind.BOOLEAN)
        Char::class.javaPrimitiveType, java.lang.Character::class.java -> WireType.Primitive(WireType.Primitive.Kind.STRING)
        else -> null
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -q test -Dtest=TypeExtractorTest`
Expected: 9 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/
git commit -m "Add TypeExtractor: primitives, enums, objects, collections

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Jackson awareness — @JsonProperty, @JsonIgnore

Add helpers that consult Jackson annotations on a field/getter, and override `walkFields` to use them.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/extract/JacksonNames.kt`
- Modify: `src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt` — `walkFields` honors `JacksonNames`.
- Create: `src/test/kotlin/community/flock/wirespec/extractor/extract/JacksonNamesTest.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/JacksonDto.kt`

- [ ] **Step 1: Write the fixture**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/JacksonDto.kt
package community.flock.wirespec.extractor.fixtures.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

data class JacksonDto(
    @field:JsonProperty("user_id") val userId: String,
    @field:JsonIgnore val internalNote: String,
    val visible: String,
)
// Note: @field: use-site target is required so the annotation lands on the
// generated backing field (where reflection looks). Without it, the annotation
// only lives on the constructor parameter.
```

- [ ] **Step 2: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/extract/JacksonNamesTest.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.fixtures.dto.JacksonDto
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JacksonNamesTest {

    @Test
    fun `JsonProperty rename is honored`() {
        val f = JacksonDto::class.java.getDeclaredField("userId")
        JacksonNames.effectiveName(f, original = "userId") shouldBe "user_id"
    }

    @Test
    fun `JsonIgnore makes the field ignored`() {
        val f = JacksonDto::class.java.getDeclaredField("internalNote")
        JacksonNames.isIgnored(f) shouldBe true
    }

    @Test
    fun `Unannotated field uses original name`() {
        val f = JacksonDto::class.java.getDeclaredField("visible")
        JacksonNames.effectiveName(f, original = "visible") shouldBe "visible"
        JacksonNames.isIgnored(f) shouldBe false
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest=JacksonNamesTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/extract/JacksonNames.kt
package community.flock.wirespec.extractor.extract

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import java.lang.reflect.AnnotatedElement

object JacksonNames {

    fun effectiveName(element: AnnotatedElement, original: String): String =
        element.getAnnotation(JsonProperty::class.java)?.value
            ?.takeIf { it.isNotEmpty() && it != JsonProperty.USE_DEFAULT_NAME }
            ?: original

    fun isIgnored(element: AnnotatedElement): Boolean =
        element.isAnnotationPresent(JsonIgnore::class.java)
}
```

- [ ] **Step 5: Modify TypeExtractor.walkFields to honor Jackson**

Replace the `walkFields` method body in `TypeExtractor.kt`:

```kotlin
protected open fun walkFields(cls: Class<*>): List<WireType.Field> {
    val members = propertyMembers(cls)
    return members.mapNotNull { (name, type) ->
        val element = cls.declaredFieldOrNull(name) ?: cls
        if (JacksonNames.isIgnored(element)) null
        else WireType.Field(
            name = JacksonNames.effectiveName(element, original = name),
            type = extractInner(type, nullable = false),
        )
    }
}

private fun Class<*>.declaredFieldOrNull(name: String): java.lang.reflect.Field? =
    try { getDeclaredField(name) } catch (_: NoSuchFieldException) { null }
```

- [ ] **Step 6: Add a TypeExtractor test that exercises Jackson**

Append to `TypeExtractorTest.kt`:

```kotlin
@Test
fun `walkFields honors @JsonProperty and @JsonIgnore`() {
    extractor.extract(community.flock.wirespec.extractor.fixtures.dto.JacksonDto::class.java)
    val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "JacksonDto" } as WireType.Object
    obj.fields.map { it.name } shouldBe listOf("user_id", "visible")
}
```

- [ ] **Step 7: Run all type tests**

Run: `mvn -q test -Dtest='TypeExtractorTest,JacksonNamesTest'`
Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/extract/JacksonNames.kt src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt src/test/kotlin/community/flock/wirespec/extractor/extract/JacksonNamesTest.kt src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/JacksonDto.kt
git commit -m "TypeExtractor: honor Jackson @JsonProperty and @JsonIgnore

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Bean Validation constraints

Detect and apply `@Pattern`, `@Size`, `@Min`, `@Max` to produce `Refined` types or annotate primitives. `@NotNull` / `@NotBlank` participate in nullability (Task 12).

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/extract/ValidationConstraints.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/extract/ValidationConstraintsTest.kt`
- Modify: `src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt` — apply constraints when walking fields.
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/ValidatedDto.kt`

- [ ] **Step 1: Write the fixture**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/ValidatedDto.kt
package community.flock.wirespec.extractor.fixtures.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class ValidatedDto(
    @field:Pattern(regexp = "^[A-Z]{3}$") val code: String,
    @field:Size(min = 1, max = 10) val name: String,
    @field:Min(0) @field:Max(120) val age: Int,
)
```

- [ ] **Step 2: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/extract/ValidationConstraintsTest.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.fixtures.dto.ValidatedDto
import community.flock.wirespec.extractor.model.WireType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ValidationConstraintsTest {

    @Test
    fun `@Pattern produces a refined String type`() {
        val f = ValidatedDto::class.java.getDeclaredField("code")
        val refined = ValidationConstraints.refine(f, base = WireType.Primitive(WireType.Primitive.Kind.STRING))
        refined.shouldBeInstanceOf<WireType.Refined>().regex shouldBe "^[A-Z]{3}$"
    }

    @Test
    fun `@Size produces refined String with min and max`() {
        val f = ValidatedDto::class.java.getDeclaredField("name")
        val refined = ValidationConstraints.refine(f, base = WireType.Primitive(WireType.Primitive.Kind.STRING))
        refined.shouldBeInstanceOf<WireType.Refined>()
        refined.min shouldBe "1"
        refined.max shouldBe "10"
    }

    @Test
    fun `@Min and @Max on Integer produce refined Integer with bounds`() {
        val f = ValidatedDto::class.java.getDeclaredField("age")
        val refined = ValidationConstraints.refine(f, base = WireType.Primitive(WireType.Primitive.Kind.INTEGER_32))
        refined.shouldBeInstanceOf<WireType.Refined>()
        refined.min shouldBe "0"
        refined.max shouldBe "120"
    }

    @Test
    fun `Unconstrained field returns the base type unchanged`() {
        val f = community.flock.wirespec.extractor.fixtures.dto.UserDto::class.java.getDeclaredField("id")
        val out = ValidationConstraints.refine(f, base = WireType.Primitive(WireType.Primitive.Kind.STRING))
        out shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest=ValidationConstraintsTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/extract/ValidationConstraints.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.WireType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.lang.reflect.AnnotatedElement
import java.util.UUID

object ValidationConstraints {

    /**
     * If [element] carries Bean Validation constraints that refine [base],
     * return a [WireType.Refined] capturing them; otherwise return [base].
     * Refined types get a synthetic name unique to this refinement.
     */
    fun refine(element: AnnotatedElement, base: WireType): WireType {
        if (base !is WireType.Primitive) return base

        val pattern = element.getAnnotation(Pattern::class.java)?.regexp
        val size = element.getAnnotation(Size::class.java)
        val min = element.getAnnotation(Min::class.java)?.value?.toString()
        val max = element.getAnnotation(Max::class.java)?.value?.toString()

        val hasAny = pattern != null || size != null || min != null || max != null
        if (!hasAny) return base

        return WireType.Refined(
            name = "Refined" + UUID.randomUUID().toString().take(8).uppercase(),
            base = base.copy(nullable = false),
            regex = pattern,
            min = min ?: size?.min?.toString(),
            max = max ?: size?.max?.toString()?.takeIf { size.max != Int.MAX_VALUE },
            nullable = base.nullable,
        )
    }

    fun isRequired(element: AnnotatedElement): Boolean =
        element.isAnnotationPresent(NotNull::class.java) ||
        element.isAnnotationPresent(NotBlank::class.java)
}
```

- [ ] **Step 5: Modify TypeExtractor.walkFields to apply constraints**

Replace `walkFields` again:

```kotlin
protected open fun walkFields(cls: Class<*>): List<WireType.Field> {
    val members = propertyMembers(cls)
    return members.mapNotNull { (name, type) ->
        val element = cls.declaredFieldOrNull(name) ?: cls
        if (JacksonNames.isIgnored(element)) return@mapNotNull null
        val rawType = extractInner(type, nullable = false)
        val refined = ValidationConstraints.refine(element, rawType)
        if (refined is WireType.Refined) _definitions += refined
        WireType.Field(
            name = JacksonNames.effectiveName(element, original = name),
            type = if (refined is WireType.Refined) WireType.Ref(refined.name) else refined,
        )
    }
}
```

(The `_definitions` reference inside the open `walkFields` requires it to be accessible — promote it to `protected` in the class declaration, or change the registration to call a `protected fun register(t: WireType)` on the extractor. Use the `protected` access on `_definitions` for simplicity:)

In `TypeExtractor`, change:
```kotlin
private val _definitions = linkedSetOf<WireType>()
```
to:
```kotlin
protected val _definitions = linkedSetOf<WireType>()
```

- [ ] **Step 6: Run all tests**

Run: `mvn -q test`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/extract/ValidationConstraints.kt src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt src/test/kotlin/community/flock/wirespec/extractor/extract/ValidationConstraintsTest.kt src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/ValidatedDto.kt
git commit -m "Apply Bean Validation constraints in TypeExtractor

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: springdoc @Schema description + nullability resolution

Add a `NullabilityResolver` that consults the full priority chain (primitives → Kotlin metadata → Optional → JSR-305 / @Nullable → @NotNull / @NotBlank / @Schema(required) → default nullable). Apply it inside TypeExtractor. Read `@Schema(description = ...)` and attach it to the field's `description`.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/extract/NullabilityResolver.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/extract/NullabilityResolverTest.kt`
- Modify: `src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt` — apply nullability and pull `@Schema(description)`.
- Create: `src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/SchemaDto.kt`

- [ ] **Step 1: Write the fixture**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/SchemaDto.kt
package community.flock.wirespec.extractor.fixtures.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.Optional

data class SchemaDto(
    @field:Schema(description = "The user's display name", required = true)
    @field:NotNull
    val name: String,

    val maybe: Optional<String>,

    val nullable: String?,
    val notNullablePrimitive: Int,
)
```

- [ ] **Step 2: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/extract/NullabilityResolverTest.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.fixtures.dto.SchemaDto
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NullabilityResolverTest {

    @Test
    fun `Java primitive int is non-null`() {
        val f = SchemaDto::class.java.getDeclaredField("notNullablePrimitive")
        NullabilityResolver.isNullable(f, declaredJavaType = Int::class.javaPrimitiveType!!) shouldBe false
    }

    @Test
    fun `Optional field is nullable`() {
        val f = SchemaDto::class.java.getDeclaredField("maybe")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe true
    }

    @Test
    fun `Kotlin nullable property is nullable`() {
        val f = SchemaDto::class.java.getDeclaredField("nullable")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe true
    }

    @Test
    fun `@NotNull or @Schema(required=true) flips to non-null`() {
        val f = SchemaDto::class.java.getDeclaredField("name")
        NullabilityResolver.isNullable(f, declaredJavaType = f.type) shouldBe false
    }

    @Test
    fun `Schema description is exposed`() {
        val f = SchemaDto::class.java.getDeclaredField("name")
        NullabilityResolver.schemaDescription(f) shouldBe "The user's display name"
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest=NullabilityResolverTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/extract/NullabilityResolver.kt
package community.flock.wirespec.extractor.extract

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Field
import java.util.Optional

object NullabilityResolver {

    /**
     * Returns true if the given member should be modelled as nullable in Wirespec.
     * Priority order:
     *  1. Java primitive type → non-null
     *  2. Optional<T> → nullable (Optional is itself the nullability marker —
     *     a Kotlin `val maybe: Optional<String>` is "non-null" at the Kotlin
     *     level but the value it carries is absent-able)
     *  3. Kotlin nullability metadata (kotlin.Metadata + member type)
     *  4. JSR-305 / @Nullable / @NonNull annotations
     *  5. @NotNull / @NotBlank / @Schema(required=true) → non-null
     *  6. Default → nullable
     */
    fun isNullable(element: AnnotatedElement, declaredJavaType: Class<*>): Boolean {
        if (declaredJavaType.isPrimitive) return false
        if (declaredJavaType == Optional::class.java) return true
        kotlinNullable(element)?.let { return it }
        annotationDeclaredNullable(element)?.let { return it }
        if (element.isAnnotationPresent(NotNull::class.java)) return false
        if (element.isAnnotationPresent(NotBlank::class.java)) return false
        if (element.getAnnotation(Schema::class.java)?.required == true) return false
        return true
    }

    fun schemaDescription(element: AnnotatedElement): String? =
        element.getAnnotation(Schema::class.java)?.description?.takeIf { it.isNotBlank() }

    /**
     * Read Kotlin's @Metadata to determine nullability for a property field.
     * Returns null when this isn't a Kotlin class member.
     */
    private fun kotlinNullable(element: AnnotatedElement): Boolean? {
        val field = element as? Field ?: return null
        val owner = field.declaringClass
        if (!owner.isAnnotationPresent(Metadata::class.java)) return null
        val kClass = try { owner.kotlin } catch (_: Throwable) { return null }
        val prop = kClass.members.firstOrNull { it.name == field.name } ?: return null
        return prop.returnType.isMarkedNullable
    }

    private fun annotationDeclaredNullable(element: AnnotatedElement): Boolean? {
        val annotations = element.annotations.map { it.annotationClass.simpleName }
        return when {
            annotations.any { it.equals("Nullable", ignoreCase = true) } -> true
            annotations.any { it.equals("NonNull", ignoreCase = true) || it.equals("NotNull", ignoreCase = true) } -> false
            else -> null
        }
    }
}
```

- [ ] **Step 5: Apply nullability in TypeExtractor.walkFields**

Replace `walkFields`:

```kotlin
protected open fun walkFields(cls: Class<*>): List<WireType.Field> {
    val members = propertyMembers(cls)
    return members.mapNotNull { (name, type) ->
        val field = cls.declaredFieldOrNull(name)
        val element: java.lang.reflect.AnnotatedElement = field ?: cls
        if (JacksonNames.isIgnored(element)) return@mapNotNull null

        val declaredClass = (type as? Class<*>) ?: ((type as? java.lang.reflect.ParameterizedType)?.rawType as? Class<*>) ?: Any::class.java
        val nullable = NullabilityResolver.isNullable(element, declaredClass)

        val rawType = withNullability(extractInner(type, nullable = false), nullable)
        val refined = ValidationConstraints.refine(element, rawType)
        if (refined is WireType.Refined) _definitions += refined

        WireType.Field(
            name = JacksonNames.effectiveName(element, original = name),
            type = if (refined is WireType.Refined) WireType.Ref(refined.name, nullable) else refined,
            description = NullabilityResolver.schemaDescription(element),
        )
    }
}

private fun withNullability(t: WireType, nullable: Boolean): WireType = when (t) {
    is WireType.Primitive -> t.copy(nullable = nullable)
    is WireType.Ref       -> t.copy(nullable = nullable)
    is WireType.ListOf    -> t.copy(nullable = nullable)
    is WireType.MapOf     -> t.copy(nullable = nullable)
    is WireType.Object    -> t.copy(nullable = nullable)
    is WireType.EnumDef   -> t.copy(nullable = nullable)
    is WireType.Refined   -> t.copy(nullable = nullable)
}
```

- [ ] **Step 6: Add a TypeExtractor test that verifies SchemaDto is modeled correctly**

Append:

```kotlin
@Test
fun `SchemaDto fields combine nullability, validation, and description`() {
    extractor.extract(community.flock.wirespec.extractor.fixtures.dto.SchemaDto::class.java)
    val obj = extractor.definitions.single { (it as? WireType.Object)?.name == "SchemaDto" } as WireType.Object
    val byName = obj.fields.associateBy { it.name }
    byName["name"]!!.description shouldBe "The user's display name"
    byName["name"]!!.type.nullable shouldBe false
    byName["nullable"]!!.type.nullable shouldBe true
    byName["notNullablePrimitive"]!!.type.nullable shouldBe false
}
```

- [ ] **Step 7: Run all tests**

Run: `mvn -q test`
Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/extract/NullabilityResolver.kt src/main/kotlin/community/flock/wirespec/extractor/extract/TypeExtractor.kt src/test/kotlin/community/flock/wirespec/extractor/extract/NullabilityResolverTest.kt src/test/kotlin/community/flock/wirespec/extractor/extract/TypeExtractorTest.kt src/test/kotlin/community/flock/wirespec/extractor/fixtures/dto/SchemaDto.kt
git commit -m "Add NullabilityResolver and @Schema description support

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: TypeExtractor used by EndpointExtractor

Now that TypeExtractor is fully featured, replace the placeholder types in `EndpointExtractor` (path/query params, request body, response body).

**Files:**
- Modify: `src/main/kotlin/community/flock/wirespec/extractor/extract/ParamExtractor.kt` — accept a `TypeExtractor` and produce real types.
- Modify: `src/main/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractor.kt` — accept and pass through a `TypeExtractor`.
- Modify: tests for both — pass in a fresh `TypeExtractor()`.

- [ ] **Step 1: Modify `ParamExtractor` to take a TypeExtractor**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/extract/ParamExtractor.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.Param
import community.flock.wirespec.extractor.model.Param.Source
import community.flock.wirespec.extractor.model.WireType
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import java.lang.reflect.Method
import java.lang.reflect.Parameter

class ParamExtractor(private val types: TypeExtractor) {

    fun extractParams(method: Method): List<Param> = method.parameters.mapNotNull(::toParam)

    fun extractRequestBody(method: Method): WireType? {
        val p = method.parameters.firstOrNull { it.isAnnotationPresent(RequestBody::class.java) } ?: return null
        return types.extract(p.parameterizedType)
    }

    private fun toParam(p: Parameter): Param? {
        val type = types.extract(p.parameterizedType)
        p.getAnnotation(PathVariable::class.java)?.let { a ->
            return Param(name = a.value.ifEmpty { p.name }, source = Source.PATH, type = type)
        }
        p.getAnnotation(RequestParam::class.java)?.let { a ->
            return Param(name = a.value.ifEmpty { p.name }, source = Source.QUERY, type = type)
        }
        p.getAnnotation(RequestHeader::class.java)?.let { a ->
            return Param(name = a.value.ifEmpty { p.name }, source = Source.HEADER, type = type)
        }
        p.getAnnotation(CookieValue::class.java)?.let { a ->
            return Param(name = a.value.ifEmpty { p.name }, source = Source.COOKIE, type = type)
        }
        return null
    }
}
```

- [ ] **Step 2: Modify `EndpointExtractor` to take a TypeExtractor**

Replace the `EndpointExtractor` `object` with a class:

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/extract/EndpointExtractor.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.Param
import community.flock.wirespec.extractor.model.WireType
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import java.lang.reflect.Method

class EndpointExtractor(private val types: TypeExtractor) {

    private val params = ParamExtractor(types)

    fun extract(controllerClass: Class<*>): List<Endpoint> {
        val classMapping = AnnotatedElementUtils.findMergedAnnotation(controllerClass, RequestMapping::class.java)
        val classPaths = classMapping?.path?.toList()?.takeIf { it.isNotEmpty() } ?: listOf("")
        return controllerClass.methods.flatMap { method -> extractFromMethod(controllerClass, classPaths, method) }
    }

    private fun extractFromMethod(controllerClass: Class<*>, classPaths: List<String>, method: Method): List<Endpoint> {
        val mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping::class.java)
            ?: return emptyList()
        val methodPaths = mapping.path.toList().takeIf { it.isNotEmpty() } ?: listOf("")
        val httpMethods = if (mapping.method.isEmpty()) listOf(RequestMethod.GET) else mapping.method.toList()

        val allParams = params.extractParams(method)
        val body = params.extractRequestBody(method)
        val unwrapped = ReturnTypeUnwrapper.unwrap(method.genericReturnType)
        val responseRef = if (unwrapped.isVoid) null else {
            val raw = types.extract(unwrapped.type)
            if (unwrapped.isList) WireType.ListOf(raw) else raw
        }
        val status = ReturnTypeUnwrapper.statusCodeFor(method, unwrapped)

        return httpMethods.flatMap { rm ->
            classPaths.flatMap { cp ->
                methodPaths.map { mp ->
                    Endpoint(
                        controllerSimpleName = controllerClass.simpleName,
                        name = pascalCase(method.name),
                        method = rm.toHttpMethod(),
                        pathSegments = parsePath(joinPath(cp, mp)),
                        queryParams = allParams.filter { it.source == Param.Source.QUERY },
                        headerParams = allParams.filter { it.source == Param.Source.HEADER },
                        cookieParams = allParams.filter { it.source == Param.Source.COOKIE },
                        requestBody = body,
                        responseBody = responseRef,
                        statusCode = status,
                    )
                }
            }
        }
    }

    private fun joinPath(a: String, b: String): String {
        val left = a.trim('/').takeIf { it.isNotBlank() }
        val right = b.trim('/').takeIf { it.isNotBlank() }
        return listOfNotNull(left, right).joinToString("/")
    }

    internal fun parsePath(path: String): List<PathSegment> =
        path.split('/').filter { it.isNotBlank() }.map { seg ->
            val match = Regex("""^\{([^:}]+)(?::[^}]+)?}$""").matchEntire(seg)
            if (match != null) PathSegment.Variable(match.groupValues[1], WireType.Primitive(WireType.Primitive.Kind.STRING))
            else PathSegment.Literal(seg)
        }

    internal fun pascalCase(name: String): String =
        if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)

    private fun RequestMethod.toHttpMethod(): HttpMethod = when (this) {
        RequestMethod.GET -> HttpMethod.GET
        RequestMethod.POST -> HttpMethod.POST
        RequestMethod.PUT -> HttpMethod.PUT
        RequestMethod.PATCH -> HttpMethod.PATCH
        RequestMethod.DELETE -> HttpMethod.DELETE
        RequestMethod.OPTIONS -> HttpMethod.OPTIONS
        RequestMethod.HEAD -> HttpMethod.HEAD
        RequestMethod.TRACE -> HttpMethod.TRACE
    }
}
```

- [ ] **Step 3: Update tests to instantiate**

In `EndpointExtractorTest.kt`, change every usage of `EndpointExtractor.extract(X::class.java)` to:

```kotlin
EndpointExtractor(TypeExtractor()).extract(X::class.java)
```

In `ParamExtractorTest.kt`, change every usage of `ParamExtractor.extractParams(...)` / `ParamExtractor.extractRequestBodyParameter(...)` to use a fresh instance:

```kotlin
private val pe = ParamExtractor(TypeExtractor())
// ...
pe.extractParams(getItem)
pe.extractRequestBody(postItem) shouldBe WireType.Primitive(WireType.Primitive.Kind.STRING)
```

(Note: `extractRequestBodyParameter` is now `extractRequestBody` and returns a `WireType`, not a `Parameter`. Adjust the test that checked `extractRequestBodyParameter(getItem) shouldBe null` to `extractRequestBody(getItem) shouldBe null`.)

- [ ] **Step 4: Run all tests**

Run: `mvn -q test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/extract/ src/test/kotlin/community/flock/wirespec/extractor/extract/
git commit -m "Wire TypeExtractor into ParamExtractor and EndpointExtractor

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: WirespecAstBuilder

Translate the internal `Endpoint` + `WireType` model to Wirespec AST nodes.

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/ast/WirespecAstBuilder.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/ast/WirespecAstBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/ast/WirespecAstBuilderTest.kt
package community.flock.wirespec.extractor.ast

import community.flock.wirespec.compiler.core.parse.ast.Endpoint as WsEndpoint
import community.flock.wirespec.compiler.core.parse.ast.Enum as WsEnum
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type as WsType
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.Param
import community.flock.wirespec.extractor.model.WireType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class WirespecAstBuilderTest {

    private val builder = WirespecAstBuilder()

    @Test
    fun `endpoint has the right method, path and response status`() {
        val ep = Endpoint(
            controllerSimpleName = "UserController",
            name = "GetUser",
            method = HttpMethod.GET,
            pathSegments = listOf(
                PathSegment.Literal("users"),
                PathSegment.Variable("id", WireType.Primitive(WireType.Primitive.Kind.STRING)),
            ),
            queryParams = emptyList(), headerParams = emptyList(), cookieParams = emptyList(),
            requestBody = null,
            responseBody = WireType.Ref("UserDto"),
            statusCode = 200,
        )

        val ws = builder.toEndpoint(ep)
        ws.identifier.value shouldBe "GetUser"
        ws.method shouldBe WsEndpoint.Method.GET
        ws.path[0].shouldBeInstanceOf<WsEndpoint.Segment.Literal>().value shouldBe "users"
        ws.path[1].shouldBeInstanceOf<WsEndpoint.Segment.Param>().identifier.value shouldBe "id"
        ws.responses.single().status shouldBe "200"
    }

    @Test
    fun `Object becomes a Wirespec Type definition`() {
        val obj = WireType.Object(
            name = "UserDto",
            fields = listOf(
                WireType.Field("id", WireType.Primitive(WireType.Primitive.Kind.STRING)),
                WireType.Field("age", WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)),
            ),
        )
        val def = builder.toDefinition(obj)
        def.shouldBeInstanceOf<WsType>().identifier.value shouldBe "UserDto"
        (def as WsType).shape.value.map { it.identifier.value } shouldBe listOf("id", "age")
    }

    @Test
    fun `EnumDef becomes a Wirespec Enum definition`() {
        val e = WireType.EnumDef("Role", listOf("ADMIN", "MEMBER"))
        val def = builder.toDefinition(e)
        def.shouldBeInstanceOf<WsEnum>()
        (def as WsEnum).entries shouldBe setOf("ADMIN", "MEMBER")
    }

    @Test
    fun `Refined String becomes a Wirespec Refined definition with regex`() {
        val r = WireType.Refined("RefinedABCD1234", WireType.Primitive(WireType.Primitive.Kind.STRING), regex = "^[A-Z]+$")
        val def = builder.toDefinition(r)
        def shouldBe def
        val ref = (def as community.flock.wirespec.compiler.core.parse.ast.Refined).reference
        ref.shouldBeInstanceOf<Reference.Primitive>()
        (ref.type as Reference.Primitive.Type.String).constraint?.value shouldBe "^[A-Z]+$"
    }

    @Test
    fun `nullable Ref becomes Custom with isNullable=true`() {
        val ref = builder.toReference(WireType.Ref("X", nullable = true))
        ref.shouldBeInstanceOf<Reference.Custom>()
        ref.isNullable shouldBe true
        ref.value shouldBe "X"
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=WirespecAstBuilderTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/ast/WirespecAstBuilder.kt
package community.flock.wirespec.extractor.ast

import community.flock.wirespec.compiler.core.parse.ast.Annotation as WsAnnotation
import community.flock.wirespec.compiler.core.parse.ast.Comment
import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint as WsEndpoint
import community.flock.wirespec.compiler.core.parse.ast.Enum as WsEnum
import community.flock.wirespec.compiler.core.parse.ast.Field as WsField
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Refined as WsRefined
import community.flock.wirespec.compiler.core.parse.ast.Type as WsType
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.Param
import community.flock.wirespec.extractor.model.WireType

class WirespecAstBuilder {

    fun toEndpoint(ep: Endpoint): WsEndpoint = WsEndpoint(
        comment = null,
        annotations = emptyList(),
        identifier = DefinitionIdentifier(ep.name),
        method = ep.method.toWs(),
        path = ep.pathSegments.map { it.toWs() },
        queries = ep.queryParams.map { it.toField() },
        headers = ep.headerParams.map { it.toField() },
        requests = listOf(
            WsEndpoint.Request(
                content = ep.requestBody?.let { WsEndpoint.Content("application/json", toReference(it)) }
            )
        ),
        responses = listOf(
            WsEndpoint.Response(
                status = ep.statusCode.toString(),
                headers = emptyList(),
                content = ep.responseBody?.let { WsEndpoint.Content("application/json", toReference(it)) },
                annotations = emptyList(),
            )
        ),
    )

    fun toDefinition(wt: WireType): Definition = when (wt) {
        is WireType.Object -> WsType(
            comment = wt.description?.let { Comment(it) },
            annotations = emptyList(),
            identifier = DefinitionIdentifier(wt.name),
            shape = WsType.Shape(
                value = wt.fields.map { f ->
                    WsField(
                        annotations = emptyList(),
                        identifier = FieldIdentifier(f.name),
                        reference = toReference(f.type),
                    )
                }
            ),
            extends = emptyList(),
        )
        is WireType.EnumDef -> WsEnum(
            comment = wt.description?.let { Comment(it) },
            annotations = emptyList(),
            identifier = DefinitionIdentifier(wt.name),
            entries = wt.values.toSet(),
        )
        is WireType.Refined -> WsRefined(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier(wt.name),
            reference = toReference(wt.base) as Reference.Primitive,
        )
        else -> throw IllegalArgumentException("Not a top-level definition: $wt")
    }

    fun toReference(wt: WireType): Reference = when (wt) {
        is WireType.Primitive -> primitiveRef(wt)
        is WireType.Ref       -> Reference.Custom(wt.name, wt.nullable)
        is WireType.ListOf    -> Reference.Iterable(toReference(wt.element), wt.nullable)
        is WireType.MapOf     -> Reference.Dict(toReference(wt.value), wt.nullable)
        is WireType.Object    -> Reference.Custom(wt.name, wt.nullable)
        is WireType.EnumDef   -> Reference.Custom(wt.name, wt.nullable)
        is WireType.Refined   -> Reference.Custom(wt.name, wt.nullable)
    }

    private fun primitiveRef(p: WireType.Primitive): Reference.Primitive {
        val type: Reference.Primitive.Type = when (p.kind) {
            WireType.Primitive.Kind.STRING     -> Reference.Primitive.Type.String(constraint = null)
            WireType.Primitive.Kind.INTEGER_32 -> Reference.Primitive.Type.Integer(precision = Reference.Primitive.Type.Precision.P32, constraint = null)
            WireType.Primitive.Kind.INTEGER_64 -> Reference.Primitive.Type.Integer(precision = Reference.Primitive.Type.Precision.P64, constraint = null)
            WireType.Primitive.Kind.NUMBER_32  -> Reference.Primitive.Type.Number(precision = Reference.Primitive.Type.Precision.P32, constraint = null)
            WireType.Primitive.Kind.NUMBER_64  -> Reference.Primitive.Type.Number(precision = Reference.Primitive.Type.Precision.P64, constraint = null)
            WireType.Primitive.Kind.BOOLEAN    -> Reference.Primitive.Type.Boolean
            WireType.Primitive.Kind.BYTES      -> Reference.Primitive.Type.Bytes
        }
        return Reference.Primitive(type, p.nullable)
    }

    private fun PathSegment.toWs(): WsEndpoint.Segment = when (this) {
        is PathSegment.Literal  -> WsEndpoint.Segment.Literal(value)
        is PathSegment.Variable -> WsEndpoint.Segment.Param(FieldIdentifier(name), toReference(type))
    }

    private fun Param.toField(): WsField = WsField(
        annotations = emptyList(),
        identifier = FieldIdentifier(name),
        reference = toReference(type),
    )

    private fun HttpMethod.toWs(): WsEndpoint.Method = when (this) {
        HttpMethod.GET -> WsEndpoint.Method.GET
        HttpMethod.POST -> WsEndpoint.Method.POST
        HttpMethod.PUT -> WsEndpoint.Method.PUT
        HttpMethod.PATCH -> WsEndpoint.Method.PATCH
        HttpMethod.DELETE -> WsEndpoint.Method.DELETE
        HttpMethod.OPTIONS -> WsEndpoint.Method.OPTIONS
        HttpMethod.HEAD -> WsEndpoint.Method.HEAD
        HttpMethod.TRACE -> WsEndpoint.Method.TRACE
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=WirespecAstBuilderTest`
Expected: 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/ast/ src/test/kotlin/community/flock/wirespec/extractor/ast/
git commit -m "Add WirespecAstBuilder

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: Emitter — render and write `.ws` files

Wrap `WirespecEmitter` to render an AST per file. Manage on-disk file hygiene (delete old `*.ws`, write new ones).

**Files:**
- Create: `src/main/kotlin/community/flock/wirespec/extractor/emit/Emitter.kt`
- Create: `src/test/kotlin/community/flock/wirespec/extractor/emit/EmitterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/community/flock/wirespec/extractor/emit/EmitterTest.kt
package community.flock.wirespec.extractor.emit

import community.flock.wirespec.extractor.ast.WirespecAstBuilder
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.WireType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.writeText

class EmitterTest {

    private val builder = WirespecAstBuilder()
    private val emitter = Emitter()

    @Test
    fun `writes one ws file per controller and a shared types ws`(@TempDir dir: Path) {
        val ep = builder.toEndpoint(Endpoint(
            controllerSimpleName = "HelloController",
            name = "Hello",
            method = HttpMethod.GET,
            pathSegments = listOf(PathSegment.Literal("hello")),
            queryParams = emptyList(), headerParams = emptyList(), cookieParams = emptyList(),
            requestBody = null,
            responseBody = WireType.Primitive(WireType.Primitive.Kind.STRING),
            statusCode = 200,
        ))
        val typeDef = builder.toDefinition(WireType.Object(
            name = "UserDto",
            fields = listOf(WireType.Field("id", WireType.Primitive(WireType.Primitive.Kind.STRING))),
        ))

        emitter.write(
            outputDir = dir.toFile(),
            controllerEndpoints = mapOf("HelloController" to listOf(ep)),
            sharedTypes = listOf(typeDef),
        )

        val files = dir.toFile().listFiles()!!.toList()
        files.map { it.name }.sorted() shouldHaveSize 2
        val hello = File(dir.toFile(), "HelloController.ws").readText()
        hello shouldContain "endpoint Hello GET /hello"
        val types = File(dir.toFile(), "types.ws").readText()
        types shouldContain "type UserDto"
    }

    @Test
    fun `deletes existing ws files but leaves other files alone`(@TempDir dir: Path) {
        File(dir.toFile(), "stale.ws").writeText("// stale")
        val keepMe = File(dir.toFile(), "README.md").apply { writeText("keep") }

        emitter.write(outputDir = dir.toFile(), controllerEndpoints = emptyMap(), sharedTypes = emptyList())

        File(dir.toFile(), "stale.ws").exists() shouldBe false
        keepMe.exists() shouldBe true
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=EmitterTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Write the implementation**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/emit/Emitter.kt
package community.flock.wirespec.extractor.emit

import arrow.core.NonEmptyList
import arrow.core.toNonEmptyListOrNull
import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.compiler.core.parse.ast.Module
import community.flock.wirespec.compiler.core.parse.ast.Root
import community.flock.wirespec.compiler.utils.Logger
import community.flock.wirespec.emitters.wirespec.WirespecEmitter
import java.io.File

class Emitter {

    private val emitter = WirespecEmitter()
    private val logger = object : Logger() {}

    /**
     * Render and write all `.ws` files into [outputDir].
     *
     * - Deletes existing `*.ws` files in [outputDir] recursively.
     * - Writes one `<ControllerName>.ws` per entry of [controllerEndpoints].
     * - Writes one `types.ws` containing every definition in [sharedTypes].
     * - Never touches non-`.ws` files; never writes outside [outputDir].
     */
    fun write(
        outputDir: File,
        controllerEndpoints: Map<String, List<Definition>>,
        sharedTypes: List<Definition>,
    ) {
        outputDir.mkdirs()
        clearExistingWs(outputDir)

        controllerEndpoints.forEach { (controller, defs) ->
            defs.toNonEmptyListOrNull()?.let { nel ->
                val path = File(outputDir, "$controller.ws")
                path.writeText(render(nel, "$controller.ws"))
            }
        }

        sharedTypes.toNonEmptyListOrNull()?.let { nel ->
            val path = File(outputDir, "types.ws")
            path.writeText(render(nel, "types.ws"))
        }
    }

    private fun render(defs: NonEmptyList<Definition>, fileName: String): String {
        val ast = Root(
            modules = NonEmptyList(
                head = Module(fileUri = FileUri(fileName), statements = defs),
                tail = emptyList(),
            )
        )
        return emitter.emit(ast, logger).head.result
    }

    private fun clearExistingWs(dir: File) {
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "ws" }
            .forEach { it.delete() }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=EmitterTest`
Expected: 2 tests, 0 failures.

**Two API-shape risks worth verifying before committing the implementation:**
- `Logger`: if the abstract `object : Logger() {}` doesn't compile (open class with abstract members), inspect with `gh api repos/flock-community/wirespec/contents/src/compiler/utils/src/commonMain/kotlin/community/flock/wirespec/compiler/utils/Logger.kt --jq .content | base64 -d` and implement the abstract members inline.
- `FileUri(fileName)`: assumed here to be a value class with a String constructor. If it's a factory or has a different signature, inspect with `gh api repos/flock-community/wirespec/contents/src/compiler/core/src/commonMain/kotlin/community/flock/wirespec/compiler/core/FileUri.kt --jq .content | base64 -d` and adjust the call site.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/emit/ src/test/kotlin/community/flock/wirespec/extractor/emit/
git commit -m "Add Emitter: write .ws files via WirespecEmitter

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: ExtractMojo — wire the full pipeline

Replace the placeholder `execute()` with the full pipeline. Add error handling per the spec's table.

**Files:**
- Modify: `src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt`

- [ ] **Step 1: Replace `execute()` with the wired pipeline**

```kotlin
// src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt
package community.flock.wirespec.extractor

import community.flock.wirespec.extractor.ast.WirespecAstBuilder
import community.flock.wirespec.extractor.classpath.ClasspathBuilder
import community.flock.wirespec.extractor.emit.Emitter
import community.flock.wirespec.extractor.extract.EndpointExtractor
import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.scan.ControllerScanner
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File

@Mojo(
    name = "extract",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true,
)
class ExtractMojo : AbstractMojo() {

    @Parameter(required = true, property = "wirespec.output")
    lateinit var output: File

    @Parameter(property = "wirespec.basePackage")
    var basePackage: String? = null

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject

    override fun execute() {
        val classesDir = File(project.build.outputDirectory)
        if (!classesDir.exists() || classesDir.listFiles().isNullOrEmpty()) {
            throw MojoExecutionException(
                "No compiled classes in ${classesDir.absolutePath}. Did `compile` run before `wirespec:extract`?"
            )
        }
        if (output.exists() && !output.canWrite()) {
            throw MojoExecutionException("Output dir not writable: ${output.absolutePath}")
        }

        val urls = ClasspathBuilder.collectUrls(
            runtimeClasspathElements = project.runtimeClasspathElements,
            outputDirectory = classesDir,
        )
        val loader = ClasspathBuilder.fromUrls(urls, parent = javaClass.classLoader)

        val scanPackages = listOfNotNull(basePackage)
        val controllers = ControllerScanner.scan(loader, scanPackages, basePackage)
        log.info("Found ${controllers.size} controller(s)")

        val types = TypeExtractor()
        val endpoints = EndpointExtractor(types)
        val builder = WirespecAstBuilder()

        val byController = controllers.associate { c ->
            val eps = try {
                endpoints.extract(c).map(builder::toEndpoint)
            } catch (t: Throwable) {
                log.warn("Skipping ${c.name}: ${t.message}")
                emptyList()
            }
            c.simpleName to eps.map { it as community.flock.wirespec.compiler.core.parse.ast.Definition }
        }.filterValues { it.isNotEmpty() }

        val sharedTypes = types.definitions.mapNotNull { def ->
            try { builder.toDefinition(def) } catch (t: Throwable) {
                log.warn("Skipping type ${def}: ${t.message}"); null
            }
        }

        Emitter().write(
            outputDir = output,
            controllerEndpoints = byController,
            sharedTypes = sharedTypes,
        )
        log.info("Wrote ${byController.size + (if (sharedTypes.isEmpty()) 0 else 1)} .ws file(s) to ${output.absolutePath}")
    }
}
```

- [ ] **Step 2: Verify the project still builds**

Run: `mvn -q clean install -DskipTests`
Expected: `BUILD SUCCESS`. Inspect `target/classes/META-INF/maven/plugin.xml` to confirm parameters are still wired correctly.

- [ ] **Step 3: Run all unit tests**

Run: `mvn -q test`
Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/community/flock/wirespec/extractor/ExtractMojo.kt
git commit -m "Wire full pipeline in ExtractMojo

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 16: Maven Invoker integration test

End-to-end test using `maven-invoker-plugin`: compile a small Spring app, run `wirespec:extract` against it, verify the produced `.ws` files via a Groovy verify script.

**Files:**
- Modify: `pom.xml` — add `maven-invoker-plugin` execution.
- Create: `src/it/basic-spring-app/pom.xml`
- Create: `src/it/basic-spring-app/invoker.properties`
- Create: `src/it/basic-spring-app/verify.groovy`
- Create: `src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java`
- Create: `src/it/basic-spring-app/src/main/java/com/acme/api/dto/UserDto.java`
- Create: `src/it/basic-spring-app/src/main/java/com/acme/api/dto/Role.java`

- [ ] **Step 1: Add `maven-invoker-plugin` execution to root `pom.xml`**

Add inside `<build><plugins>`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-invoker-plugin</artifactId>
    <version>3.9.0</version>
    <configuration>
        <projectsDirectory>src/it</projectsDirectory>
        <cloneProjectsTo>${project.build.directory}/it</cloneProjectsTo>
        <pomIncludes><pomInclude>*/pom.xml</pomInclude></pomIncludes>
        <postBuildHookScript>verify</postBuildHookScript>
        <localRepositoryPath>${project.build.directory}/local-repo</localRepositoryPath>
        <settingsFile>src/it/settings.xml</settingsFile>
        <goals><goal>clean</goal><goal>compile</goal><goal>wirespec:extract</goal></goals>
    </configuration>
    <executions>
        <execution>
            <id>integration-test</id>
            <goals><goal>install</goal><goal>integration-test</goal><goal>verify</goal></goals>
        </execution>
    </executions>
</plugin>
```

Also create `src/it/settings.xml` with a minimal default profile (empty `<settings/>` is fine).

- [ ] **Step 2: Write the fixture Spring app**

```java
// src/it/basic-spring-app/pom.xml — skeleton; uses Spring web only, no Spring Boot
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>basic-spring-app</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
            <version>6.1.14</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>community.flock.wirespec.extractor</groupId>
                <artifactId>wirespec-extractor-maven-plugin</artifactId>
                <version>@project.version@</version>
                <configuration>
                    <output>${project.build.directory}/wirespec</output>
                    <basePackage>com.acme.api</basePackage>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

```java
// src/it/basic-spring-app/src/main/java/com/acme/api/dto/Role.java
package com.acme.api.dto;
public enum Role { ADMIN, MEMBER }
```

```java
// src/it/basic-spring-app/src/main/java/com/acme/api/dto/UserDto.java
package com.acme.api.dto;
import java.util.List;
public record UserDto(String id, int age, boolean active, Role role, List<String> tags) {}
```

```java
// src/it/basic-spring-app/src/main/java/com/acme/api/UserController.java
package com.acme.api;

import com.acme.api.dto.UserDto;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {
    @GetMapping("/{id}")
    public UserDto getUser(@PathVariable String id) { return null; }

    @PostMapping
    public UserDto createUser(@RequestBody UserDto body) { return body; }
}
```

```properties
# src/it/basic-spring-app/invoker.properties
invoker.goals = clean compile wirespec:extract
```

```groovy
// src/it/basic-spring-app/verify.groovy
import java.nio.file.Files

def wsDir = new File(basedir, "target/wirespec")
assert wsDir.isDirectory(), "wirespec output dir missing"

def files = wsDir.listFiles().collect { it.name }.sort()
assert files == ["UserController.ws", "types.ws"], "unexpected files: $files"

def controller = new File(wsDir, "UserController.ws").text
assert controller.contains("endpoint GetUser GET /users/{id"), "GetUser endpoint missing or wrong path: \n$controller"
assert controller.contains("endpoint CreateUser POST"),         "CreateUser endpoint missing: \n$controller"

def types = new File(wsDir, "types.ws").text
assert types.contains("type UserDto"), "UserDto type missing: \n$types"
assert types.contains("Role"),         "Role enum missing: \n$types"

return true
```

- [ ] **Step 3: Run the integration test**

Run: `mvn -q clean install`
Expected: `BUILD SUCCESS`. The verify script returns true; a failure shows the script's assertion message.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/it/
git commit -m "Add maven-invoker integration test with fixture Spring app

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 17: README

Add a top-level README with usage, configuration, and a small example.

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write the README**

````markdown
# wirespec-extractor-maven-plugin

A Maven plugin that scans a Spring Boot application's compiled classes and emits
[Wirespec](https://wirespec.io) (`.ws`) files describing its HTTP endpoints and DTO types.

## Usage

```xml
<plugin>
  <groupId>community.flock.wirespec.extractor</groupId>
  <artifactId>wirespec-extractor-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <output>${project.build.directory}/wirespec</output>
    <!-- optional: only scan classes under this package -->
    <basePackage>com.acme.api</basePackage>
  </configuration>
</plugin>
```

The plugin auto-binds to the `process-classes` phase, so a normal `mvn compile`
will produce `.ws` files in `target/wirespec/`. To run it manually:

```bash
mvn wirespec:extract
```

## What it extracts

- `@RestController` / `@Controller` (with `@ResponseBody`) classes
- The `@RequestMapping` family (`@GetMapping`, `@PostMapping`, etc.)
- Path / query / header / cookie parameters; `@RequestBody`
- Response types, including unwrapping of `ResponseEntity`, `Mono`, `Flux`,
  `Optional`, `Callable`, `DeferredResult`
- DTO classes referenced by endpoints, with Jackson (`@JsonProperty`,
  `@JsonIgnore`), Bean Validation (`@NotNull`, `@Size`, `@Pattern`, `@Min`,
  `@Max`), and springdoc `@Schema` awareness

See [docs/superpowers/specs/2026-05-12-wirespec-extractor-design.md](docs/superpowers/specs/2026-05-12-wirespec-extractor-design.md)
for the full design and current scope limitations.

## Output layout

- One `<ControllerName>.ws` per controller (endpoints)
- One shared `types.ws` (all referenced DTO types)
- The output directory is treated as a generated artifact: existing `*.ws`
  files are deleted on each run; non-`.ws` files are left alone.
````

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "Add README

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Self-review checklist (run after all tasks complete)

- [ ] All tasks pass `mvn -q clean verify`
- [ ] `target/classes/META-INF/maven/plugin.xml` lists `extract` with `output` + `basePackage`
- [ ] Integration test under `src/it/basic-spring-app/` passes
- [ ] `git log` shows one focused commit per task
- [ ] No `TODO`/`TBD` markers in `src/main/`
