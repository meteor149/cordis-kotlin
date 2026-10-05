package org.cordis.packager

import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.MergingReport
import com.android.manifmerger.ManifestSystemProperty
import com.android.utils.ILogger
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** Build an uninstalled APK from existing library compilation; never recompile commonMain. */
@CacheableTask
abstract class PrepareAndroidPluginTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val library: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val privateLibraries: ConfigurableFileCollection
    @get:Classpath abstract val compileClasspath: ConfigurableFileCollection
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val targetSdk: Property<Int>
    @get:Input abstract val entryPoint: Property<String>
    @get:Input abstract val sharedPackages: SetProperty<String>
    @get:Input abstract val sharedClasses: SetProperty<String>
    @get:Input abstract val excludedPayloadPaths: SetProperty<String>
    @get:Input abstract val incompleteAndroidDependencies: ListProperty<String>
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val aapt2: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val zipalign: RegularFileProperty
    @get:Classpath abstract val d8Classpath: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val javaExecutable: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val javacExecutable: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Inject abstract val execOperations: ExecOperations

    init { incompleteAndroidDependencies.convention(emptyList()) }

    @TaskAction
    fun prepare() {
        require(incompleteAndroidDependencies.get().isEmpty()) {
            "Apply io.github.meteor149.cordis.packager to private Android library projects to publish complete AARs: ${incompleteAndroidDependencies.get()}"
        }
        require(packageName.get().matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) { "Invalid Android package name" }
        require(minSdk.get() > 0 && targetSdk.get() >= minSdk.get()) { "Invalid Android SDK range" }
        val work = File(temporaryDir, "android")
        work.deleteRecursively()
        work.mkdirs()
        val privateFiles = privateLibraries.files.sortedWith(compareBy<File> { it.name }.thenBy { org.cordis.packages.packageFileSha256(it) })
        val artifacts = (listOf(library.get().asFile) + privateFiles).distinct()
        val aars = artifacts.filter { it.extension == "aar" }.mapIndexed { index, file -> unpackAar(file, File(work, "aar/$index")) }
        require(aars.isNotEmpty()) { "Android library input must be an AAR" }
        val namespaces = aars.mapNotNull { aar ->
            File(aar, "AndroidManifest.xml").takeIf { it.exists() }?.let { xml(it).documentElement.getAttribute("package").takeIf(String::isNotBlank) }
        }.toSet()
        val merged = mergeManifest(work, aars)
        val generated = File(work, "generated").apply { mkdirs() }
        val resourcesApk = File(work, "resources.apk")
        // The module's own resources have overlay priority over its private dependencies.
        val compiledResources = aars.asReversed().mapIndexedNotNull { index, aar ->
            File(aar, "res").takeIf { it.isDirectory && it.walkTopDown().any(File::isFile) }?.let { res ->
                File(work, "resources-$index.zip").also { output -> runTool(aapt2.get().asFile, listOf("compile", "--dir", res.absolutePath, "-o", output.absolutePath)) }
            }
        }
        val link = mutableListOf("link", "-o", resourcesApk.absolutePath, "--manifest", merged.absolutePath,
            "-I", androidJar.get().asFile.absolutePath, "--java", generated.absolutePath, "--auto-add-overlay",
            "--min-sdk-version", minSdk.get().toString(), "--target-sdk-version", targetSdk.get().toString())
        if (namespaces.isNotEmpty()) link.addAll(listOf("--extra-packages", namespaces.sorted().joinToString(":")))
        compiledResources.forEach { link.addAll(listOf("-R", it.absolutePath)) }
        runTool(aapt2.get().asFile, link)

        val contents = JarContents(sharedPackages.get(), sharedClasses.get())
        fun generatedR(path: String): Boolean = namespaces.any { namespace ->
            val prefix = namespace.replace('.', '/') + "/R"
            path == "$prefix.class" || path.startsWith("$prefix$") && path.endsWith(".class")
        }
        artifacts.filter { it.extension == "jar" }.forEach { contents.addJar(it, ::generatedR) }
        aars.forEach { aar ->
            File(aar, "classes.jar").takeIf(File::isFile)?.let { contents.addJar(it, ::generatedR) }
            File(aar, "libs").walkTopDown().filter { it.isFile && it.extension == "jar" }.forEach { contents.addJar(it, ::generatedR) }
        }
        val javaSources = generated.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        if (javaSources.isNotEmpty()) {
            val classes = File(work, "resourceClasses").apply { mkdirs() }
            val arguments = listOf("--release", "17", "-encoding", "UTF-8", "-d", classes.absolutePath) + javaSources.map(File::getAbsolutePath)
            val response = File(work, "javac.args")
            response.writeText(arguments.joinToString("\n") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" })
            runTool(javacExecutable.get().asFile, listOf("@${response.absolutePath}"))
            classes.walkTopDown().filter(File::isFile).forEach { contents.add(it.relativeTo(classes).invariantSeparatorsPath, it.readBytes()) }
        }
        contents.requireEntry(entryPoint.get())
        val program = File(work, "program.jar")
        contents.write(program)
        val dex = File(work, "dex").apply { mkdirs() }
        val classpathJars = compileClasspath.files.flatMapIndexed { index, file ->
            when (file.extension) {
                "jar" -> listOf(file)
                "aar" -> {
                    val dir = unpackAar(file, File(work, "compile/$index"))
                    listOf(File(dir, "classes.jar")).filter(File::exists) + File(dir, "libs").walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
                }
                else -> emptyList()
            }
        }
        execOperations.javaexec {
            executable = javaExecutable.get().asFile.absolutePath
            classpath(d8Classpath)
            mainClass.set("com.android.tools.r8.D8")
            maxHeapSize = "1g"
            args("--release", "--min-api", minSdk.get().toString(), "--lib", androidJar.get().asFile.absolutePath, "--output", dex.absolutePath)
            classpathJars.forEach { args("--classpath", it.absolutePath) }
            args(program.absolutePath)
        }.assertNormalExitValue()

        val entries = sortedMapOf<String, ByteArray>()
        fun add(path: String, bytes: ByteArray) {
            org.cordis.packages.validatePackagePath(path)
            if (excludedPayloadPaths.get().any { path == it || path.startsWith("${it.trimEnd('/')}/") }) return
            val old = entries[path]
            require(old == null || old.contentEquals(bytes)) { "Conflicting Android payload: $path" }
            entries[path] = bytes
        }
        ZipFile(resourcesApk).use { zip -> zip.entries().asSequence().filterNot { it.isDirectory }.forEach { add(it.name, zip.getInputStream(it).use { stream -> stream.readBytes() }) } }
        dex.listFiles().orEmpty().filter { it.extension == "dex" }.forEach { add(it.name, it.readBytes()) }
        ZipFile(program).use { zip -> zip.entries().asSequence().filterNot { it.isDirectory || it.name.endsWith(".class") }.forEach {
            require(it.name != "AndroidManifest.xml" && it.name != "resources.arsc" && !it.name.matches(Regex("classes[0-9]*\\.dex"))) { "Reserved Android Java resource: ${it.name}" }
            add(it.name, zip.getInputStream(it).use { stream -> stream.readBytes() })
        } }
        aars.forEach { aar ->
            listOf("assets" to "assets", "jni" to "lib").forEach { (source, destination) ->
                val dir = File(aar, source)
                dir.walkTopDown().filter(File::isFile).forEach { add("$destination/${it.relativeTo(dir).invariantSeparatorsPath}", it.readBytes()) }
            }
        }
        require("classes.dex" in entries && "AndroidManifest.xml" in entries) { "Incomplete plugin APK" }
        val unaligned = File(work, "unaligned.apk")
        writeZip(unaligned, entries, setOf("resources.arsc"))
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        runTool(zipalign.get().asFile, listOf("-f", "4", unaligned.absolutePath, output.absolutePath))
    }

    private fun mergeManifest(work: File, aars: List<File>): File {
        val main = manifest.orNull?.asFile ?: File(work, "main.xml").apply {
            writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${packageName.get()}"><application /></manifest>""")
        }
        val log = object : ILogger {
            override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) { logger.error(msgFormat?.let { String.format(it, *args) }, t) }
            override fun warning(msgFormat: String, vararg args: Any?) { logger.warn(String.format(msgFormat, *args)) }
            override fun info(msgFormat: String, vararg args: Any?) { logger.info(String.format(msgFormat, *args)) }
            override fun verbose(msgFormat: String, vararg args: Any?) { logger.debug(String.format(msgFormat, *args)) }
        }
        val merger = ManifestMerger2.newMerger(main, log, ManifestMerger2.MergeType.APPLICATION)
            .setOverride(ManifestSystemProperty.Document.PACKAGE, packageName.get())
            .setOverride(ManifestSystemProperty.UsesSdk.MIN_SDK_VERSION, minSdk.get().toString())
            .setOverride(ManifestSystemProperty.UsesSdk.TARGET_SDK_VERSION, targetSdk.get().toString())
        aars.forEach { merger.addLibraryManifest(File(it, "AndroidManifest.xml")) }
        val report = merger.merge()
        require(report.result != MergingReport.Result.ERROR) { report.reportString }
        return File(work, "AndroidManifest.xml").apply { writeText(requireNotNull(report.getMergedDocument(MergingReport.MergedManifestKind.MERGED))) }
    }
}

internal fun unpackAar(file: File, destination: File): File {
    destination.mkdirs()
    ZipFile(file).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
            org.cordis.packages.validatePackagePath(entry.name)
            val target = File(destination, entry.name)
            require(target.toPath().normalize().startsWith(destination.toPath().normalize())) { "Unsafe AAR path" }
            require(!target.exists()) { "Duplicate AAR entry: ${entry.name}" }
            target.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
    return destination
}

internal fun xml(file: File) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
}.newDocumentBuilder().parse(file)

private fun PrepareAndroidPluginTask.runTool(executable: File, arguments: List<String>) {
    execOperations.exec { commandLine(listOf(executable.absolutePath) + arguments) }.assertNormalExitValue()
}

