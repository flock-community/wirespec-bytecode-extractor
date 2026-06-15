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
    fun `collectUrls combines runtime classpath with output dirs`() {
        val classesDir = File("/tmp/classes")
        val kotlinClassesDir = File("/tmp/kotlin-classes")
        val jar = File("/tmp/dep.jar")

        val urls = ClasspathBuilder.collectUrls(
            runtimeClasspathElements = listOf(jar.absolutePath),
            outputDirectories = listOf(classesDir, kotlinClassesDir),
        )

        urls shouldContain classesDir.toURI().toURL()
        urls shouldContain kotlinClassesDir.toURI().toURL()
        urls shouldContain jar.toURI().toURL()
    }
}
