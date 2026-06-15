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
