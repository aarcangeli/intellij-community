// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet", "ReplacePutWithAssignment", "ReplaceJavaStaticMethodWithKotlinAnalog")

package org.jetbrains.intellij.build.impl

import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PathUtilRt
import com.intellij.util.io.URLUtil
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.impl.BaseLayout.Companion.APP_JAR
import org.jetbrains.intellij.build.impl.projectStructureMapping.DistributionFileEntry
import org.jetbrains.intellij.build.impl.projectStructureMapping.ModuleLibraryFileEntry
import org.jetbrains.intellij.build.impl.projectStructureMapping.ModuleOutputEntry
import org.jetbrains.intellij.build.impl.projectStructureMapping.ProjectLibraryEntry
import org.jetbrains.intellij.build.tasks.Source
import org.jetbrains.intellij.build.tasks.ZipSource
import org.jetbrains.intellij.build.tasks.addModuleSources
import org.jetbrains.intellij.build.tasks.buildJars
import org.jetbrains.jps.model.java.JpsJavaClasspathKind
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.library.JpsLibrary
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.model.module.JpsLibraryDependency
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.jps.model.module.JpsModuleReference
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@Suppress("ReplaceJavaStaticMethodWithKotlinAnalog")
private val libsThatUsedInJps = java.util.Set.of(
  "ASM",
  "aalto-xml",
  "netty-buffer",
  "netty-codec-http",
  "netty-handler-proxy",
  "fastutil-min",
  "gson",
  "Log4J",
  "Slf4j",
  "slf4j-jdk14",
  // see getBuildProcessApplicationClasspath - used in JPS
  "lz4-java",
  "maven-resolver-provider",
  "OroMatcher",
  "jgoodies-forms",
  "jgoodies-common",
  "NanoXML",
  // see ArtifactRepositoryManager.getClassesFromDependencies
  "plexus-utils",
  "Guava",
  "http-client",
  "commons-codec",
  "commons-logging",
  "commons-lang3",
  "kotlin-stdlib-jdk8"
)

class JarPackager private constructor(private val context: BuildContext) {
  private val jarDescriptors = LinkedHashMap<Path, JarDescriptor>()
  private val projectStructureMapping = ConcurrentLinkedQueue<DistributionFileEntry>()
  private val libToMetadata = HashMap<JpsLibrary, ProjectLibraryData>()

  companion object {
    private val extraMergeRules = LinkedHashMap<String, (String) -> Boolean>()

    init {
      extraMergeRules.put("groovy.jar") { it.startsWith("org.codehaus.groovy:") }
      extraMergeRules.put("jsch-agent.jar") { it.startsWith("jsch-agent") }
      // see ClassPathUtil.getUtilClassPath
      extraMergeRules.put("3rd-party-rt.jar") {
        libsThatUsedInJps.contains(it) || it.startsWith("kotlinx-") || it == "kotlin-reflect"
      }
    }

    @JvmStatic
    fun getLibraryName(lib: JpsLibrary): String {
      val name = lib.name
      if (!name.startsWith("#")) {
        return name
      }

      val roots = lib.getRoots(JpsOrderRootType.COMPILED)
      if (roots.size != 1) {
        throw IllegalStateException("Non-single entry module library $name: ${roots.joinToString { it.url }}")
      }
      return PathUtilRt.getFileName(roots.first().url.removeSuffix(URLUtil.JAR_SEPARATOR))
    }

    @JvmStatic
    fun pack(actualModuleJars: Map<String, List<String>>, outputDir: Path, context: BuildContext) {
      pack(actualModuleJars = actualModuleJars,
           outputDir = outputDir,
           layout = BaseLayout(),
           moduleOutputPatcher = ModuleOutputPatcher(),
           dryRun = false,
           context = context)
    }

    @JvmStatic
    fun pack(actualModuleJars: Map<String, List<String>>,
             outputDir: Path,
             layout: BaseLayout,
             moduleOutputPatcher: ModuleOutputPatcher,
             dryRun: Boolean,
             context: BuildContext): Collection<DistributionFileEntry> {
      val copiedFiles = HashMap<Path, JpsLibrary>()
      val packager = JarPackager(context)
      for (data in layout.includedModuleLibraries) {
        val library = context.findRequiredModule(data.moduleName).libraryCollection.libraries
                        .find { getLibraryName(it) == data.libraryName }
                      ?: throw IllegalArgumentException("Cannot find library ${data.libraryName} in \'${data.moduleName}\' module")
        var fileName = libNameToMergedJarFileName(data.libraryName)
        var relativePath = data.relativeOutputPath
        var targetFile: Path? = null
        if (relativePath.endsWith(".jar")) {
          val index = relativePath.lastIndexOf('/')
          if (index == -1) {
            fileName = relativePath
            relativePath = ""
          }
          else {
            fileName = relativePath.substring(index + 1)
            relativePath = relativePath.substring(0, index)
          }
        }
        if (!relativePath.isEmpty()) {
          targetFile = outputDir.resolve(relativePath).resolve(fileName)
        }
        if (targetFile == null) {
          targetFile = outputDir.resolve(fileName)
        }
        packager.addLibrary(library = library,
                            targetFile = targetFile!!,
                            files = getLibraryFiles(library = library, copiedFiles = copiedFiles, isModuleLevel = true))
      }

      val extraLibSources = HashMap<String, MutableList<Source>>()
      val libraryToMerge = packager.packProjectLibraries(jarToModuleNames = actualModuleJars,
                                                         outputDir = outputDir,
                                                         layout = layout,
                                                         copiedFiles = copiedFiles,
                                                         extraLibSources = extraLibSources)
      val isRootDir = context.paths.distAllDir == outputDir.parent
      if (isRootDir) {
        for ((key, value) in extraMergeRules) {
          packager.mergeLibsByPredicate(key, libraryToMerge, outputDir, value)
        }
        if (!libraryToMerge.isEmpty()) {
          packager.filesToSourceWithMappings(outputDir.resolve(APP_JAR), libraryToMerge)
        }
      }
      else if (!libraryToMerge.isEmpty()) {
        val mainJarName = (layout as PluginLayout).getMainJarName()
        assert(actualModuleJars.containsKey(mainJarName))
        packager.filesToSourceWithMappings(outputDir.resolve(mainJarName), libraryToMerge)
      }

      // must be concurrent - buildJars executed in parallel
      val moduleNameToSize = ConcurrentHashMap<String, Int>()
      for ((jarPath, modules) in actualModuleJars) {
        val jarFile = outputDir.resolve(jarPath)
        val descriptor = packager.jarDescriptors.computeIfAbsent(jarFile) { JarDescriptor(jarFile = it) }
        val includedModules = descriptor.includedModules
        if (includedModules == null) {
          descriptor.includedModules = modules.toMutableList()
        }
        else {
          includedModules.addAll(modules)
        }

        val sourceList = descriptor.sources
        extraLibSources.get(jarPath)?.let(sourceList::addAll)
        packager.packModuleOutputAndUnpackedProjectLibraries(modules = modules,
                                                             jarPath = jarPath,
                                                             jarFile = jarFile,
                                                             moduleOutputPatcher = moduleOutputPatcher,
                                                             layout = layout,
                                                             moduleNameToSize = moduleNameToSize,
                                                             sourceList = sourceList)
      }

      val entries = ArrayList<Triple<Path, String, List<Source>>>(packager.jarDescriptors.size)
      val isReorderingEnabled = !context.options.buildStepsToSkip.contains(BuildOptions.GENERATE_JAR_ORDER_STEP)
      for (descriptor in packager.jarDescriptors.values) {
        var pathInClassLog = ""
        if (isReorderingEnabled) {
          if (isRootDir) {
            pathInClassLog = outputDir.parent.relativize(descriptor.jarFile).toString().replace(File.separatorChar, '/')
          }
          else if (outputDir.startsWith(context.paths.distAllDir)) {
            pathInClassLog = context.paths.distAllDir.relativize(descriptor.jarFile).toString().replace(File.separatorChar, '/')
          }
          else {
            val parent = outputDir.parent
            if (parent?.fileName.toString() == "plugins") {
              pathInClassLog = outputDir.parent.parent.relativize(descriptor.jarFile).toString().replace(File.separatorChar, '/')
            }
          }
        }
        entries.add(Triple(descriptor.jarFile, pathInClassLog, descriptor.sources))
      }

      buildJars(entries, dryRun)

      for (item in packager.jarDescriptors.values) {
        for (moduleName in (item.includedModules ?: emptyList())) {
          val size = moduleNameToSize.get(moduleName)
                     ?: throw IllegalStateException("Size is not set for $moduleName (moduleNameToSize=$moduleNameToSize)")
          packager.projectStructureMapping.add(ModuleOutputEntry(path = item.jarFile, moduleName, size))
        }
      }
      return packager.projectStructureMapping
    }

    @JvmStatic
    fun getSearchableOptionsDir(buildContext: BuildContext): Path = buildContext.paths.tempDir.resolve("searchableOptionsResult")
  }

  private fun mergeLibsByPredicate(jarName: String,
                                   libraryToMerge: MutableMap<JpsLibrary, List<Path>>,
                                   outputDir: Path,
                                   predicate: (String) -> Boolean) {
    val result = LinkedHashMap<JpsLibrary, List<Path>>()
    val iterator = libraryToMerge.entries.iterator()
    while (iterator.hasNext()) {
      val (key, value) = iterator.next()
      if (predicate(key.name)) {
        iterator.remove()
        result.put(key, value)
      }
    }
    if (result.isEmpty()) {
      return
    }
    filesToSourceWithMappings(outputDir.resolve(jarName), result)
  }

  private fun filesToSourceWithMappings(uberJarFile: Path, libraryToMerge: Map<JpsLibrary, List<Path>>) {
    val sources = getJarDescriptorSources(uberJarFile)
    for ((key, value) in libraryToMerge) {
      filesToSourceWithMapping(sources, value, key, uberJarFile)
    }
  }

  private fun packModuleOutputAndUnpackedProjectLibraries(modules: Collection<String>,
                                                          jarPath: String,
                                                          jarFile: Path,
                                                          moduleOutputPatcher: ModuleOutputPatcher,
                                                          layout: BaseLayout,
                                                          moduleNameToSize: MutableMap<String, Int>,
                                                          sourceList: MutableList<Source>) {
    val searchableOptionsDir = getSearchableOptionsDir(context)
    Span.current().addEvent("include module outputs", Attributes.of(AttributeKey.stringArrayKey("modules"), java.util.List.copyOf(modules)))
    for (moduleName in modules) {
      addModuleSources(moduleName, moduleNameToSize, context.getModuleOutputDir(context.findRequiredModule(moduleName)),
                       moduleOutputPatcher.getPatchedDir(moduleName), moduleOutputPatcher.getPatchedContent(moduleName),
                       searchableOptionsDir, layout.moduleExcludes[moduleName], sourceList)
    }
    for (libraryName in layout.projectLibrariesToUnpack.get(jarPath)) {
      val library = context.project.libraryCollection.findLibrary(libraryName)
      if (library == null) {
        context.messages.error("Project library \'$libraryName\' from $jarPath should be unpacked but it isn\'t found")
        continue
      }

      for (ioFile in library.getFiles(JpsOrderRootType.COMPILED)) {
        val file = ioFile.toPath()
        sourceList.add(ZipSource(file = file) { size: Int ->
          val libraryData = ProjectLibraryData(libraryName = library.name,
                                               outPath = null,
                                               packMode = LibraryPackMode.MERGED,
                                               reason = "explicitUnpack")
          projectStructureMapping.add(ProjectLibraryEntry(jarFile, libraryData, file, size))
        })
      }
    }
  }

  private fun packProjectLibraries(jarToModuleNames: Map<String, List<String>>,
                                   outputDir: Path,
                                   layout: BaseLayout,
                                   copiedFiles: MutableMap<Path, JpsLibrary>,
                                   extraLibSources: MutableMap<String, MutableList<Source>>): MutableMap<JpsLibrary, List<Path>> {
    val toMerge = LinkedHashMap<JpsLibrary, List<Path>>()
    val projectLibs = if (layout.includedProjectLibraries.isEmpty()) {
      emptyList()
    }
    else {
      layout.includedProjectLibraries.sortedBy { it.libraryName }
    }

    for (libraryData in projectLibs) {
      val library = context.project.libraryCollection.findLibrary(libraryData.libraryName)
                    ?: throw IllegalArgumentException("Cannot find library ${libraryData.libraryName} in the project")
      libToMetadata.put(library, libraryData)
      val libName = library.name
      val files = getLibraryFiles(library = library, copiedFiles = copiedFiles, isModuleLevel = false)
      var packMode = libraryData.packMode
      if (packMode == LibraryPackMode.MERGED && !extraMergeRules.values.any { it(libName) } && !isLibraryMergeable(libName)) {
        packMode = LibraryPackMode.STANDALONE_MERGED
      }
      val outPath = libraryData.outPath
      if (packMode == LibraryPackMode.MERGED && outPath == null) {
        toMerge.put(library, files)
      }
      else {
        var libOutputDir = outputDir
        if (outPath != null) {
          libOutputDir = if (outPath.endsWith(".jar")) {
            addLibrary(library, outputDir.resolve(outPath), files)
            continue
          }
          else {
            outputDir.resolve(outPath)
          }
        }
        if (packMode == LibraryPackMode.STANDALONE_MERGED) {
          addLibrary(library, libOutputDir.resolve(libNameToMergedJarFileName(libName)), files)
        }
        else {
          for (file in files) {
            var fileName = file.fileName.toString()
            if (packMode == LibraryPackMode.STANDALONE_SEPARATE_WITHOUT_VERSION_NAME) {
              fileName = removeVersionFromJar(fileName)
            }
            addLibrary(library, libOutputDir.resolve(fileName), listOf(file))
          }
        }
      }
    }
    for ((targetFilename, value) in jarToModuleNames) {
      if (targetFilename.contains("/")) {
        continue
      }
      for (moduleName in value) {
        if (layout.modulesWithExcludedModuleLibraries.contains(moduleName)) {
          continue
        }

        val excluded = layout.excludedModuleLibraries.get(moduleName)
        for (element in context.findRequiredModule(moduleName).dependenciesList.dependencies) {
          if (element !is JpsLibraryDependency) {
            continue
          }

          packModuleLibs(moduleName = moduleName,
                         targetFilename = targetFilename,
                         libraryDependency = element,
                         excluded = excluded,
                         layout = layout,
                         outputDir = outputDir,
                         copiedFiles = copiedFiles,
                         extraLibSources = extraLibSources)
        }
      }
    }
    return toMerge
  }

  private fun packModuleLibs(moduleName: String,
                             targetFilename: String,
                             libraryDependency: JpsLibraryDependency,
                             excluded: Collection<String>,
                             layout: BaseLayout,
                             outputDir: Path,
                             copiedFiles: MutableMap<Path, JpsLibrary>,
                             extraLibSources: MutableMap<String, MutableList<Source>>) {
    if (libraryDependency.libraryReference.parentReference!!.resolve() !is JpsModule) {
      return
    }

    if (JpsJavaExtensionService.getInstance().getDependencyExtension(libraryDependency)?.scope
        ?.isIncludedIn(JpsJavaClasspathKind.PRODUCTION_RUNTIME) != true) {
      return
    }

    val library = libraryDependency.library!!
    val libraryName = getLibraryName(library)
    if (excluded.contains(libraryName) || layout.includedModuleLibraries.any { it.libraryName == libraryName }) {
      return
    }

    val files = getLibraryFiles(library = library, copiedFiles = copiedFiles, isModuleLevel = true)
    if (library.name == "async-profiler-windows") {
      // custom name, removeVersionFromJar doesn't support strings like `2.1-ea-4`
      addLibrary(library, outputDir.resolve("async-profiler-windows.jar"), files)
      return
    }

    for (i in (files.size - 1) downTo 0) {
      val file = files.get(i)
      val fileName = file.fileName.toString()
      if (fileName.endsWith("-rt.jar") || fileName.contains("-agent") || fileName == "yjp-controller-api-redist.jar") {
        files.removeAt(i)
        addLibrary(library, outputDir.resolve(removeVersionFromJar(fileName)), listOf(file))
      }
    }

    if (!files.isEmpty()) {
      val sources = extraLibSources.computeIfAbsent(targetFilename) { mutableListOf() }
      val targetFile = outputDir.resolve(targetFilename)
      for (file in files) {
        sources.add(ZipSource(file = file) { size ->
          projectStructureMapping.add(ModuleLibraryFileEntry(targetFile, moduleName, file, size))
        })
      }
    }
  }

  private fun filesToSourceWithMapping(to: MutableList<Source>, files: List<Path>, library: JpsLibrary, targetFile: Path) {
    val moduleReference = library.createReference().parentReference as? JpsModuleReference
    for (file in files) {
      to.add(ZipSource(file) { size ->
        if (moduleReference == null) {
          projectStructureMapping.add(ProjectLibraryEntry(path = targetFile,
                                                          data = libToMetadata.get(library)!!,
                                                          libraryFile = file,
                                                          size = size))
        }
        else {
          projectStructureMapping.add(ModuleLibraryFileEntry(path = targetFile,
                                                             moduleName = moduleReference.moduleName,
                                                             libraryFile = file,
                                                             size = size))
        }
      })
    }
  }

  private fun addLibrary(library: JpsLibrary, targetFile: Path, files: List<Path>) {
    filesToSourceWithMapping(getJarDescriptorSources(targetFile), files, library, targetFile)
  }

  private fun getJarDescriptorSources(targetFile: Path): MutableList<Source> {
    return jarDescriptors.computeIfAbsent(targetFile) { JarDescriptor(targetFile) }.sources
  }
}

private data class JarDescriptor(val jarFile: Path) {
  val sources: MutableList<Source> = mutableListOf()
  var includedModules: MutableList<String>? = null
}

private fun removeVersionFromJar(fileName: String): String {
  val matcher = LayoutBuilder.JAR_NAME_WITH_VERSION_PATTERN.matcher(fileName)
  return if (matcher.matches()) "${matcher.group(1)}.jar" else fileName
}

private fun getLibraryFiles(library: JpsLibrary, copiedFiles: MutableMap<Path, JpsLibrary>, isModuleLevel: Boolean): MutableList<Path> {
  val files = library.getPaths(JpsOrderRootType.COMPILED)
  val libName = library.name
  for (file in files) {
    val alreadyCopiedFor = copiedFiles.putIfAbsent(file, library)
    if (alreadyCopiedFor != null) {
      // check name - we allow to have same named module level library name
      if (isModuleLevel && alreadyCopiedFor.name == libName) {
        continue
      }

      throw IllegalStateException("File $file from $libName is already provided by ${alreadyCopiedFor.name} library")
    }
  }
  return files
}

private fun libNameToMergedJarFileName(libName: String): String {
  return "${FileUtil.sanitizeFileName(libName.lowercase(Locale.getDefault()), false)}.jar"
}

@Suppress("SpellCheckingInspection")
private val excludedFromMergeLibs = java.util.Set.of(
  "sqlite", "async-profiler",
  "dexlib2", // android-only lib
  "intellij-test-discovery", // used as an agent
  "winp", "junixsocket-core", "pty4j", "grpc-netty-shaded", // these contain a native library
  "protobuf", // https://youtrack.jetbrains.com/issue/IDEA-268753
)

private fun isLibraryMergeable(libName: String): Boolean {
  return !excludedFromMergeLibs.contains(libName) &&
         !libName.startsWith("kotlin-") &&
         !libName.startsWith("kotlinc.") &&
         !libName.startsWith("projector-") &&
         !libName.contains("-agent-") &&
         !libName.startsWith("rd-") &&
         !libName.contains("annotations", ignoreCase = true) &&
         !libName.startsWith("junit", ignoreCase = true) &&
         !libName.startsWith("cucumber-", ignoreCase = true) &&
         !libName.contains("groovy", ignoreCase = true)
}