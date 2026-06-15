package community.flock.wirespec.extractor.classpath

import java.io.File
import java.net.URL
import java.net.URLClassLoader

object ClasspathBuilder {

    /** Build a child URLClassLoader over the given URLs. */
    fun fromUrls(urls: List<URL>, parent: ClassLoader): URLClassLoader =
        URLClassLoader(urls.toTypedArray(), parent)

    /**
     * Combine class output directories with the runtime classpath into the
     * URL list to feed a URLClassLoader. The output dirs come first (in order)
     * so the project's own classes win over duplicates pulled in transitively.
     */
    fun collectUrls(runtimeClasspathElements: List<String>, outputDirectories: List<File>): List<URL> {
        val outputs = outputDirectories.map { it.toURI().toURL() }
        val deps = runtimeClasspathElements.map { File(it).toURI().toURL() }
        return (outputs + deps).distinct()
    }
}
