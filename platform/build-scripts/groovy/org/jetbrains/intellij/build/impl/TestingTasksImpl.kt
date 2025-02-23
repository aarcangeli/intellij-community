// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplacePutWithAssignment")

package org.jetbrains.intellij.build.impl

import com.intellij.TestCaseLoader
import com.intellij.diagnostic.telemetry.use
import com.intellij.diagnostic.telemetry.useWithScope
import com.intellij.execution.CommandLineWrapperUtil
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.util.lang.UrlClassLoader
import io.opentelemetry.api.common.AttributeKey
import org.jetbrains.intellij.build.*
import org.jetbrains.intellij.build.CompilationTasks.Companion.create
import org.jetbrains.intellij.build.TraceManager.spanBuilder
import org.jetbrains.intellij.build.causal.CausalProfilingOptions
import org.jetbrains.intellij.build.impl.compilation.PortableCompilationCache
import org.jetbrains.intellij.build.io.runProcess
import org.jetbrains.jps.model.java.JpsJavaClasspathKind
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.java.JpsJavaSdkType
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.util.JpsPathUtil
import java.io.*
import java.lang.reflect.Modifier
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.BiConsumer
import java.util.function.Predicate
import java.util.regex.Pattern
import java.util.stream.Stream
import kotlin.streams.toList

class TestingTasksImpl(private val context: CompilationContext, private val options: TestingOptions) : TestingTasks {
  override fun runTests(additionalJvmOptions: List<String>, defaultMainModule: String?, rootExcludeCondition: Predicate<File>?) {
    if (options.testDiscoveryEnabled && options.performanceTestsOnly) {
      context.messages.buildStatus("Skipping performance testing with Test Discovery, {build.status.text}")
      return
    }

    checkOptions()
    val compilationTasks = create(context)
    val projectArtifacts = if (options.beforeRunProjectArtifacts == null) {
      null
    }
    else {
      options.beforeRunProjectArtifacts.split(';').dropLastWhile(String::isEmpty).toSet()
    }

    if (projectArtifacts != null) {
      compilationTasks.buildProjectArtifacts(projectArtifacts)
    }

    val runConfigurations = options.testConfigurations?.splitToSequence(';')
      ?.filter(String::isNotEmpty)
      ?.map {
        val file = RunConfigurationProperties.findRunConfiguration(context.paths.projectHomeDir, it)
        JUnitRunConfigurationProperties.loadRunConfiguration(file)
      }
      ?.toList()
    if (runConfigurations != null) {
      compilationTasks.compileModules(listOf("intellij.tools.testsBootstrap"),
                                      listOf("intellij.platform.buildScripts") + runConfigurations.map { it.moduleName })
      compilationTasks.buildProjectArtifacts(runConfigurations.flatMapTo(LinkedHashSet()) { it.requiredArtifacts })
    }
    else if (options.mainModule != null) {
      compilationTasks.compileModules(listOf("intellij.tools.testsBootstrap"), listOf(options.mainModule, "intellij.platform.buildScripts"))
    }
    else {
      compilationTasks.compileAllModulesAndTests()
    }

    val remoteDebugJvmOptions = System.getProperty("teamcity.remote-debug.jvm.options")
    if (remoteDebugJvmOptions != null) {
      debugTests(remoteDebugJvmOptions, additionalJvmOptions, defaultMainModule, rootExcludeCondition, context)
    }
    else {
      val additionalSystemProperties = LinkedHashMap<String, String>()
      val effectiveAdditionalJvmOptions = additionalJvmOptions.toMutableList()
      loadTestDiscovery(effectiveAdditionalJvmOptions, additionalSystemProperties)
      if (runConfigurations == null) {
        runTestsFromGroupsAndPatterns(effectiveAdditionalJvmOptions, defaultMainModule, rootExcludeCondition, additionalSystemProperties, context)
      }
      else {
        runTestsFromRunConfigurations(effectiveAdditionalJvmOptions, runConfigurations, additionalSystemProperties, context)
      }
      if (options.testDiscoveryEnabled) {
        publishTestDiscovery(context.messages, testDiscoveryTraceFilePath)
      }
    }
  }

  private fun checkOptions() {
    if (options.testConfigurations != null) {
      val testConfigurationsOptionName = "intellij.build.test.configurations"
      if (options.testPatterns != null) {
        warnOptionIgnored(testConfigurationsOptionName, "intellij.build.test.patterns")
      }
      if (options.testGroups != TestingOptions.ALL_EXCLUDE_DEFINED_GROUP) {
        warnOptionIgnored(testConfigurationsOptionName, "intellij.build.test.groups")
      }
      if (options.mainModule != null) {
        warnOptionIgnored(testConfigurationsOptionName, "intellij.build.test.main.module")
      }
    }
    else if (options.testPatterns != null && options.testGroups != TestingOptions.ALL_EXCLUDE_DEFINED_GROUP) {
      warnOptionIgnored("intellij.build.test.patterns", "intellij.build.test.groups")
    }
    if (options.batchTestIncludes != null && !isRunningInBatchMode) {
      context.messages
        .warning("'intellij.build.test.batchTest.includes' option will be ignored as other tests matching options are specified.")
    }
  }

  private fun warnOptionIgnored(specifiedOption: String, ignoredOption: String) {
    context.messages.warning("\'$specifiedOption\' option is specified so \'$ignoredOption\' will be ignored.")
  }

  private fun runTestsFromRunConfigurations(additionalJvmOptions: List<String>,
                                            runConfigurations: List<JUnitRunConfigurationProperties>,
                                            additionalSystemProperties: Map<String, String>,
                                            context: CompilationContext) {
    for (configuration in runConfigurations) {
      spanBuilder("run \'${configuration.name}\' run configuration").useWithScope {
        runTestsFromRunConfiguration(configuration, additionalJvmOptions, additionalSystemProperties, context)
      }
    }
  }

  private fun runTestsFromRunConfiguration(runConfigurationProperties: JUnitRunConfigurationProperties,
                                           additionalJvmOptions: List<String>,
                                           additionalSystemProperties: Map<String, String>,
                                           context: CompilationContext) {
    context.messages.progress("Running \'${runConfigurationProperties.name}\' run configuration")
    runTestsProcess(mainModule = runConfigurationProperties.moduleName,
                    testGroups = null,
                    testPatterns = runConfigurationProperties.testClassPatterns.joinToString(separator = ";"),
                    jvmArgs = removeStandardJvmOptions(runConfigurationProperties.vmParameters) + additionalJvmOptions,
                    systemProperties = additionalSystemProperties,
                    envVariables = runConfigurationProperties.envVariables,
                    remoteDebugging = false,
                    context = context)
  }

  private fun runTestsFromGroupsAndPatterns(additionalJvmOptions: List<String>,
                                            defaultMainModule: String?,
                                            rootExcludeCondition: Predicate<File>?,
                                            additionalSystemProperties: MutableMap<String, String>,
                                            context: CompilationContext) {
    if (rootExcludeCondition != null) {
      val excludedRoots = ArrayList<String>()
      for (module in context.project.modules) {
        val contentRoots = module.contentRootsList.urls
        if (!contentRoots.isEmpty() && rootExcludeCondition.test(JpsPathUtil.urlToFile(contentRoots.first()))) {
          var dir = context.getModuleOutputDir(module)
          if (Files.exists(dir)) {
            excludedRoots.add(dir.toString())
          }

          dir = Path.of(context.getModuleTestsOutputPath(module))
          if (Files.exists(dir)) {
            excludedRoots.add(dir.toString())
          }
        }
      }

      val excludedRootsFile = context.paths.tempDir.resolve("excluded.classpath")
      Files.createDirectories(excludedRootsFile.parent)
      Files.writeString(excludedRootsFile, excludedRoots.joinToString(separator = "\n"))
      additionalSystemProperties.put("exclude.tests.roots.file", excludedRootsFile.toString())
    }

    runTestsProcess(mainModule = options.mainModule ?: defaultMainModule!!,
                    testGroups = options.testGroups,
                    testPatterns = options.testPatterns,
                    jvmArgs = additionalJvmOptions,
                    systemProperties = additionalSystemProperties,
                    remoteDebugging = false,
                    context = context)
  }

  private fun loadTestDiscovery(additionalJvmOptions: MutableList<String>, additionalSystemProperties: LinkedHashMap<String, String>) {
    if (!options.testDiscoveryEnabled) {
      return
    }

    val testDiscovery = "intellij-test-discovery"
    val library = context.projectModel.project.libraryCollection.findLibrary(testDiscovery)
    if (library == null) {
      throw RuntimeException("Can\'t find the $testDiscovery library, but test discovery capturing enabled.")
    }

    val agentJar = library.getPaths(JpsOrderRootType.COMPILED)
      .firstOrNull {
        val name = it.fileName.toString()
        name.startsWith("intellij-test-discovery") && name.endsWith(".jar")
      } ?: throw RuntimeException("Can\'t find the agent in $testDiscovery library, but test discovery capturing enabled.")

    additionalJvmOptions.add("-javaagent:$agentJar")
    val excludeRoots = context.projectModel.global.libraryCollection.getLibraries(JpsJavaSdkType.INSTANCE)
      .mapTo(LinkedHashSet()) { FileUtilRt.toSystemDependentName(it.properties.homePath) }

    excludeRoots.add(context.paths.buildOutputDir.toString())
    excludeRoots.add(context.paths.projectHomeDir.resolve("out").toString())

    additionalSystemProperties.put("test.discovery.listener", "com.intellij.TestDiscoveryBasicListener")
    additionalSystemProperties.put("test.discovery.data.listener",
                                   "com.intellij.rt.coverage.data.SingleTrFileDiscoveryProtocolDataListener")
    additionalSystemProperties.put("org.jetbrains.instrumentation.trace.file", testDiscoveryTraceFilePath)
    additionalSystemProperties.put("test.discovery.include.class.patterns", options.testDiscoveryIncludePatterns)

    additionalSystemProperties.put("test.discovery.include.class.patterns", options.testDiscoveryIncludePatterns)
    additionalSystemProperties.put("test.discovery.exclude.class.patterns", options.testDiscoveryExcludePatterns)

    additionalSystemProperties.put("test.discovery.excluded.roots", excludeRoots.joinToString(separator = ";"))
  }

  private val testDiscoveryTraceFilePath: String
    get() = options.testDiscoveryTraceFilePath ?: context.paths.projectHomeDir.resolve("intellij-tracing/td.tr").toString()

  private fun debugTests(remoteDebugJvmOptions: String,
                         additionalJvmOptions: List<String>,
                         defaultMainModule: String?,
                         rootExcludeCondition: Predicate<File>?,
                         context: CompilationContext) {
    val testConfigurationType = System.getProperty("teamcity.remote-debug.type")
    if (testConfigurationType != "junit") {
      context.messages.error(
        "Remote debugging is supported for junit run configurations only, but \'teamcity.remote-debug.type\' is $testConfigurationType")
    }
    val testObject = System.getProperty("teamcity.remote-debug.junit.type")
    val junitClass = System.getProperty("teamcity.remote-debug.junit.class")
    if (testObject != "class") {
      val message = "Remote debugging supports debugging all test methods in a class for now, debugging isn\'t supported for \'$testObject\'"
      if (testObject == "method") {
        context.messages.warning(message)
        context.messages.warning("Launching all test methods in the class $junitClass")
      }
      else {
        context.messages.error(message)
      }
    }
    if (junitClass == null) {
      context.messages.error("Remote debugging supports debugging all test methods in a class for now, but target class isn't specified")
    }
    if (options.testPatterns != null) {
      context.messages.warning("'intellij.build.test.patterns' option is ignored while debugging via TeamCity plugin")
    }
    if (options.testConfigurations != null) {
      context.messages.warning("'intellij.build.test.configurations' option is ignored while debugging via TeamCity plugin")
    }
    runTestsProcess(mainModule = options.mainModule ?: defaultMainModule!!,
                    testGroups = null,
                    testPatterns = junitClass,
                    jvmArgs = removeStandardJvmOptions(StringUtilRt.splitHonorQuotes(remoteDebugJvmOptions, ' ')) + additionalJvmOptions,
                    systemProperties = emptyMap(),
                    remoteDebugging = true,
                    context = context)
  }

  private fun runTestsProcess(mainModule: String,
                              testGroups: String?,
                              testPatterns: String?,
                              jvmArgs: List<String>,
                              systemProperties: Map<String, String>,
                              envVariables: Map<String, String> = emptyMap(),
                              remoteDebugging: Boolean,
                              context: CompilationContext) {
    val testsClasspath = context.getModuleRuntimeClasspath(context.findRequiredModule(mainModule), true)
    val bootstrapClasspath = context.getModuleRuntimeClasspath(context.findRequiredModule("intellij.tools.testsBootstrap"), false)
    val classpathFile = context.paths.tempDir.resolve("junit.classpath")
    Files.createDirectories(classpathFile.parent)
    val classPathString = StringBuilder()
    for (s in testsClasspath) {
      if (Files.exists(Path.of(s))) {
        classPathString.append(s).append('\n')
      }
    }
    if (classPathString.isNotEmpty()) {
      classPathString.setLength(classPathString.length - 1)
    }
    Files.writeString(classpathFile, classPathString)
    val allSystemProperties: MutableMap<String, String> = HashMap(systemProperties)
    allSystemProperties.putIfAbsent("classpath.file", classpathFile.toString())
    testPatterns?.let { allSystemProperties.putIfAbsent("intellij.build.test.patterns", it) }
    testGroups?.let { allSystemProperties.putIfAbsent("intellij.build.test.groups", it) }
    allSystemProperties.putIfAbsent("intellij.build.test.sorter", System.getProperty("intellij.build.test.sorter"))
    allSystemProperties.putIfAbsent("bootstrap.testcases", "com.intellij.AllTests")
    allSystemProperties.putIfAbsent(TestingOptions.PERFORMANCE_TESTS_ONLY_FLAG, options.performanceTestsOnly.toString())
    val allJvmArgs = ArrayList(jvmArgs)
    prepareEnvForTestRun(allJvmArgs, allSystemProperties, bootstrapClasspath.toMutableList(), remoteDebugging)
    if (isRunningInBatchMode) {
      context.messages.info("Running tests from $mainModule matched by \'${options.batchTestIncludes}\' pattern.")
    }
    else {
      context.messages.info("Starting tests from groups \'$testGroups\' from classpath of module \'$mainModule\'")
    }
    val numberOfBuckets = allSystemProperties[TestCaseLoader.TEST_RUNNERS_COUNT_FLAG]
    if (numberOfBuckets != null) {
      context.messages.info("Tests from bucket ${allSystemProperties[TestCaseLoader.TEST_RUNNER_INDEX_FLAG]}" +
                            " of $numberOfBuckets will be executed")
    }
    val runtime = runtimeExecutablePath().toString()
    context.messages.info("Runtime: $runtime")
    runProcess(listOf(runtime, "-version"), null, context.messages)
    context.messages.info("Runtime options: $allJvmArgs")
    context.messages.info("System properties: $allSystemProperties")
    context.messages.info("Bootstrap classpath: $bootstrapClasspath")
    context.messages.info("Tests classpath: $testsClasspath")
    if (!envVariables.isEmpty()) {
      context.messages.info("Environment variables: $envVariables")
    }
    runJUnit5Engine(mainModule = mainModule,
                    systemProperties = allSystemProperties,
                    jvmArgs = allJvmArgs,
                    envVariables = envVariables,
                    bootstrapClasspath = bootstrapClasspath,
                    testClasspath = testsClasspath)
    notifySnapshotBuilt(allJvmArgs)
  }

  private fun runtimeExecutablePath(): Path {
    val binJava = "bin/java"
    val binJavaExe = "bin/java.exe"
    val contentsHome = "Contents/Home"
    val runtimeDir: Path
    if (options.customRuntimePath != null) {
      runtimeDir = Path.of(options.customRuntimePath)
      check(
        Files.isDirectory(
          runtimeDir)) { "Custom Jre path from system property '" + TestingOptions.TEST_JRE_PROPERTY + "' is missing: " + runtimeDir }
    }
    else {
      runtimeDir = context.bundledRuntime.getHomeForCurrentOsAndArch()
    }
    if (SystemInfoRt.isWindows) {
      val path = runtimeDir.resolve(binJavaExe)
      check(Files.exists(path)) { "java.exe is missing: $path" }
      return path
    }
    if (SystemInfoRt.isMac) {
      if (Files.exists(runtimeDir.resolve(binJava))) {
        return runtimeDir.resolve(binJava)
      }
      if (Files.exists(runtimeDir.resolve(contentsHome).resolve(binJava))) {
        return runtimeDir.resolve(contentsHome).resolve(binJava)
      }
      throw IllegalStateException("java executable is missing under $runtimeDir")
    }
    check(Files.exists(runtimeDir.resolve(binJava))) { "java executable is missing: " + runtimeDir.resolve(binJava) }
    return runtimeDir.resolve(binJava)
  }

  private fun notifySnapshotBuilt(jvmArgs: List<String>) {
    val option = "-XX:HeapDumpPath="
    val file = Path.of(jvmArgs.first { it.startsWith(option) }.substring(option.length))
    if (Files.exists(file)) {
      context.notifyArtifactWasBuilt(file)
    }
  }

  override fun createSnapshotsDirectory(): Path {
    val snapshotsDir = context.paths.projectHomeDir.resolve("out/snapshots")
    NioFiles.deleteRecursively(snapshotsDir)
    Files.createDirectories(snapshotsDir)
    return snapshotsDir
  }

  override fun prepareEnvForTestRun(jvmArgs: MutableList<String>,
                                    systemProperties: MutableMap<String, String>,
                                    classPath: MutableList<String>,
                                    remoteDebugging: Boolean) {
    if (jvmArgs.contains("-Djava.system.class.loader=com.intellij.util.lang.UrlClassLoader")) {
      val utilModule = context.findRequiredModule("intellij.platform.util")
      val enumerator = JpsJavaExtensionService.dependencies(utilModule)
        .recursively()
        .withoutSdk()
        .includedIn(JpsJavaClasspathKind.PRODUCTION_RUNTIME)
      val utilClasspath = enumerator.classes().roots.mapTo(LinkedHashSet()) { it.absolutePath }
      utilClasspath.removeAll(classPath.toSet())
      classPath.addAll(utilClasspath)
    }
    val snapshotsDir = createSnapshotsDirectory()
    val hprofSnapshotFilePath = snapshotsDir.resolve("intellij-tests-oom.hprof").toString()
    jvmArgs.addAll(0, listOf("-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=$hprofSnapshotFilePath", "-Dkotlinx.coroutines.debug=on"))
    jvmArgs.addAll(0, VmOptionsGenerator.computeVmOptions(true, null))
    jvmArgs.addAll(options.jvmMemoryOptions?.split(whitespaceRegex) ?: listOf("-Xms750m", "-Xmx750m", "-Dsun.io.useCanonCaches=false"))
    val tempDir = System.getProperty("teamcity.build.tempDir", System.getProperty("java.io.tmpdir"))
    val defaultSystemProperties = mapOf(
      "idea.platform.prefix" to options.platformPrefix,
      "idea.home.path" to context.paths.projectHome,
      "idea.config.path" to "$tempDir/config",
      "idea.system.path" to "$tempDir/system",
      "intellij.build.compiled.classes.archives.metadata" to System.getProperty("intellij.build.compiled.classes.archives.metadata"),
      "intellij.build.compiled.classes.archive" to System.getProperty("intellij.build.compiled.classes.archive"),
      BuildOptions.PROJECT_CLASSES_OUTPUT_DIRECTORY_PROPERTY to "${context.projectOutputDirectory}",
      "idea.coverage.enabled.build" to System.getProperty("idea.coverage.enabled.build"),
      "teamcity.buildConfName" to System.getProperty("teamcity.buildConfName"),
      "java.io.tmpdir" to tempDir,
      "teamcity.build.tempDir" to tempDir,
      "teamcity.tests.recentlyFailedTests.file" to System.getProperty("teamcity.tests.recentlyFailedTests.file"),
      "teamcity.build.branch.is_default" to System.getProperty("teamcity.build.branch.is_default"),
      "jna.nosys" to "true",
      "file.encoding" to "UTF-8",
      "io.netty.leakDetectionLevel" to "PARANOID",
    )
    defaultSystemProperties.forEach(BiConsumer { k, v ->
      if (v != null) {
        systemProperties.putIfAbsent(k, v)
      }
    })
    System.getProperties().forEach(BiConsumer { key, value ->
      if ((key as String).startsWith("pass.")) {
        systemProperties.put(key.substring("pass.".length), value as String)
      }
    })
    if (PortableCompilationCache.CAN_BE_USED) {
      systemProperties.put(BuildOptions.USE_COMPILED_CLASSES_PROPERTY, "true")
    }
    var suspendDebugProcess = options.suspendDebugProcess

    if (options.performanceTestsOnly) {
      context.messages.info("Debugging disabled for performance tests")
      suspendDebugProcess = false
    }
    else if (remoteDebugging) {
      context.messages.info("Remote debugging via TeamCity plugin is activated.")
      if (suspendDebugProcess) {
        context.messages.warning("'intellij.build.test.debug.suspend' option is ignored while debugging via TeamCity plugin")
        suspendDebugProcess = false
      }
    }
    else if (options.debugEnabled) {
      jvmArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=${if (suspendDebugProcess) "y" else "n"},address=${options.debugHost}:${options.debugPort}")
    }

    if (options.enableCausalProfiling) {
      val causalProfilingOptions = CausalProfilingOptions.getIMPL()
      systemProperties.put("intellij.build.test.patterns", causalProfilingOptions.testClass.replace(".", "\\."))
      jvmArgs.addAll(buildCausalProfilingAgentJvmArg(causalProfilingOptions))
    }
    jvmArgs.addAll(getCommandLineArgumentsForOpenPackages(context))
    if (suspendDebugProcess) {
      context.messages.info(
        "\n------------->------------- The process suspended until remote debugger connects to debug port -------------<-------------\n" +
        "---------------------------------------^------^------^------^------^------^------^----------------------------------------\n"
      )
    }
  }

  override fun runTestsSkippedInHeadlessEnvironment() {
    val testsSkippedInHeadlessEnvironment = spanBuilder("loading all tests annotated with @SkipInHeadlessEnvironment").use {
      loadTestsSkippedInHeadlessEnvironment()
    }
    for (it in testsSkippedInHeadlessEnvironment) {
      options.batchTestIncludes = it.getFirst()
      options.mainModule = it.getSecond()
      runTests(additionalJvmOptions = emptyList(), defaultMainModule = null, rootExcludeCondition = null)
    }
  }

  private fun loadTestsSkippedInHeadlessEnvironment(): List<Pair<String, String>> {
    val classpath = context.project.modules.asSequence()
      .flatMap { context.getModuleRuntimeClasspath(module = it, forTests = true) }
      .distinct()
      .map { Path.of(it) }
      .toList()
    val classloader = UrlClassLoader.build().files(classpath).get()
    val testAnnotation = classloader.loadClass("com.intellij.testFramework.SkipInHeadlessEnvironment")
    return context.project.modules.parallelStream().flatMap { module ->
      val root = Path.of(context.getModuleTestsOutputPath(module))
      if (Files.exists(root)) {
        Files.walk(root).use { stream ->
          stream
            .filter { it.toString().endsWith("Test.class") }
            .map { root.relativize(it).toString() }
            .filter {
              val className = FileUtilRt.getNameWithoutExtension(it).replace('/', '.')
              val testClass = classloader.loadClass(className)
              !Modifier.isAbstract(testClass.modifiers) &&
              testClass.annotations.any { annotation -> testAnnotation.isAssignableFrom(annotation.javaClass) }
            }
            .map { Pair(it, module.name) }.toList()
        }.stream()
      }
      else {
        Stream.empty()
      }
    }
      .toList()
  }

  private fun runInBatchMode(mainModule: String,
                             systemProperties: Map<String, String>,
                             jvmArgs: List<String>,
                             envVariables: Map<String, String>,
                             bootstrapClasspath: List<String>,
                             testClasspath: List<String>) {
    val mainModuleTestsOutput = context.getModuleTestsOutputPath(context.findRequiredModule(mainModule))
    val pattern = Pattern.compile(FileUtil.convertAntToRegexp(options.batchTestIncludes))
    val root = Path.of(mainModuleTestsOutput)
    val testClasses = Files.walk(root).use { stream ->
         stream.filter { pattern.matcher(root.relativize(it).toString()).matches() }.toList()
      }

    if (testClasses.isEmpty()) {
      throw RuntimeException("No tests were found in $root with $pattern")
    }

    var noTestsInAllClasses = true
    for (path in testClasses) {
      val qName = FileUtilRt.getNameWithoutExtension(root.relativize(path).toString()).replace('/', '.')
      val files = testClasspath.map { Path.of(it)  }
      try {
        var noTests = true
        val loader = UrlClassLoader.build().files(files).get()
        val aClass = loader.loadClass(qName)
        @Suppress("UNCHECKED_CAST")
        val testAnnotation = loader.loadClass("org.junit.Test") as Class<out Annotation>
        for (m in aClass.declaredMethods) {
          if (Modifier.isPublic(m.modifiers) && m.isAnnotationPresent(testAnnotation)) {
            val exitCode = runJUnit5Engine(systemProperties, jvmArgs, envVariables, bootstrapClasspath, testClasspath, qName, m.name)
            noTests = noTests && exitCode == NO_TESTS_ERROR
          }
        }
        if (noTests) {
          val exitCode3 = runJUnit5Engine(systemProperties, jvmArgs, envVariables, bootstrapClasspath, testClasspath, qName, null)
          noTests = exitCode3 == NO_TESTS_ERROR
        }
        noTestsInAllClasses = noTestsInAllClasses && java.lang.Boolean.TRUE == noTests
      }
      catch (e: Throwable) {
        throw RuntimeException("Failed to process $qName", e)
      }
    }
    if (noTestsInAllClasses) {
      throw RuntimeException("No tests were found in $mainModule")
    }
  }

  private fun runJUnit5Engine(mainModule: String,
                              systemProperties: Map<String, String>,
                              jvmArgs: List<String>,
                              envVariables: Map<String, String>,
                              bootstrapClasspath: List<String>,
                              testClasspath: List<String>) {
    if (isRunningInBatchMode) {
      spanBuilder("run tests in batch mode")
        .setAttribute(AttributeKey.stringKey("pattern"), options.batchTestIncludes)
        .useWithScope {
          runInBatchMode(mainModule, systemProperties, jvmArgs, envVariables, bootstrapClasspath, testClasspath)
        }
    }
    else {
      val exitCode5 = spanBuilder("run junit 5 tests").useWithScope {
        runJUnit5Engine(systemProperties = systemProperties,
                        jvmArgs = jvmArgs,
                        envVariables = envVariables,
                        bootstrapClasspath = bootstrapClasspath,
                        testClasspath = testClasspath,
                        suiteName = null,
                        methodName = null)
      }
      val exitCode3 = spanBuilder("run junit 3 tests").useWithScope {
        runJUnit5Engine(systemProperties = systemProperties,
                        jvmArgs = jvmArgs,
                        envVariables = envVariables,
                        bootstrapClasspath = bootstrapClasspath,
                        testClasspath = testClasspath,
                        suiteName = options.bootstrapSuite,
                        methodName = null)
      }
      if (exitCode5 == NO_TESTS_ERROR && exitCode3 == NO_TESTS_ERROR) {
        throw RuntimeException("No tests were found in the configuration")
      }
    }
  }

  private fun runJUnit5Engine(systemProperties: Map<String, String?>,
                              jvmArgs: List<String>,
                              envVariables: Map<String, String>,
                              bootstrapClasspath: List<String>,
                              testClasspath: List<String>,
                              suiteName: String?,
                              methodName: String?): Int {
    val args = ArrayList<String>()
    args.add("-classpath")
    val classpath: MutableList<String> = ArrayList(bootstrapClasspath)
    for (libName in listOf("JUnit5", "JUnit5Launcher", "JUnit5Vintage", "JUnit5Jupiter")) {
      for (library in context.projectModel.project.libraryCollection.findLibrary(libName)!!.getFiles(JpsOrderRootType.COMPILED)) {
        classpath.add(library.absolutePath)
      }
    }
    if (!isBootstrapSuiteDefault || isRunningInBatchMode || suiteName == null) {
      classpath.addAll(testClasspath)
    }
    args.add(classpath.joinToString(separator = File.pathSeparator))
    args.addAll(jvmArgs)
    args.add("-Dintellij.build.test.runner=junit5")
    for ((k, v) in systemProperties) {
      if (v != null) {
        args.add("-D$k=$v")
      }
    }
    val runner = if (suiteName == null) "com.intellij.tests.JUnit5AllRunner" else "com.intellij.tests.JUnit5Runner"
    args.add(runner)
    if (suiteName != null) {
      args.add(suiteName)
    }
    if (methodName != null) {
      args.add(methodName)
    }
    val argFile = CommandLineWrapperUtil.createArgumentFile(args, Charset.defaultCharset())
    val runtime = runtimeExecutablePath().toString()
    context.messages.info("Starting tests on runtime $runtime")
    val builder = ProcessBuilder(runtime, "@" + argFile.absolutePath)
    builder.environment().putAll(envVariables)
    val exec = builder.start()
    val errorReader = Thread(createInputReader(exec.errorStream, System.err), "Read forked error output")
    errorReader.start()
    val outputReader = Thread(createInputReader(exec.inputStream, System.out), "Read forked output")
    outputReader.start()
    val exitCode = exec.waitFor()
    errorReader.join(360000)
    outputReader.join(360000)
    if (exitCode != 0 && exitCode != NO_TESTS_ERROR) {
      context.messages.error("Tests failed with exit code $exitCode")
    }
    return exitCode
  }

  private fun createInputReader(inputStream: InputStream, outputStream: PrintStream): Runnable {
    return Runnable {
      try {
        inputStream.bufferedReader().use { inputReader ->
          while (true) {
            outputStream.println(inputReader.readLine() ?: break)
          }
        }
      }
      catch (ignored: UnsupportedEncodingException) {
      }
      catch (e: IOException) {
        context.messages.error(e.message!!, e)
      }
    }
  }

  private val isBootstrapSuiteDefault: Boolean
    get() = options.bootstrapSuite == TestingOptions.BOOTSTRAP_SUITE_DEFAULT

  private val isRunningInBatchMode: Boolean
    get() {
      return options.batchTestIncludes != null && options.testPatterns == null && options.testConfigurations == null &&
             options.testGroups == TestingOptions.ALL_EXCLUDE_DEFINED_GROUP
    }

  private fun buildCausalProfilingAgentJvmArg(options: CausalProfilingOptions): List<String> {
    val causalProfilingJvmArgs = ArrayList<String>()
    val causalProfilerAgentName = if (SystemInfoRt.isLinux || SystemInfoRt.isMac) "liblagent.so" else null
    if (causalProfilerAgentName != null) {
      val agentArgs = options.buildAgentArgsString()
      if (agentArgs != null) {
        causalProfilingJvmArgs.add("-agentpath:${System.getProperty("teamcity.build.checkoutDir")}/$causalProfilerAgentName=$agentArgs")
      }
      else {
        context.messages.info("Could not find agent options")
      }
    }
    else {
      context.messages.info("Causal profiling is supported for Linux and Mac only")
    }
    return causalProfilingJvmArgs
  }
}

private class MyTraceFileUploader(serverUrl: String,
                                  token: String?,
                                  private val messages: BuildMessages) : TraceFileUploader(serverUrl, token) {
  override fun log(message: String) {
    messages.info(message)
  }
}

private val ignoredPrefixes = listOf("-ea", "-XX:+HeapDumpOnOutOfMemoryError", "-Xbootclasspath", "-Xmx", "-Xms", "-Didea.system.path=",
                                     "-Didea.config.path=", "-Didea.home.path=")

private fun removeStandardJvmOptions(vmOptions: List<String>): List<String> {
  return vmOptions.filter { option -> ignoredPrefixes.none(option::startsWith) }
}

private fun publishTestDiscovery(messages: BuildMessages, file: String?) {
  val serverUrl = System.getProperty("intellij.test.discovery.url")
  val token = System.getProperty("intellij.test.discovery.token")
  messages.info("Trying to upload $file into $serverUrl.")
  if (file != null && File(file).exists()) {
    if (serverUrl == null) {
      messages.warning("""
Test discovery server url is not defined, but test discovery capturing enabled. 
Will not upload to remote server. Please set 'intellij.test.discovery.url' system property.
""".trimIndent())
      return
    }

    val uploader = MyTraceFileUploader(serverUrl, token, messages)
    try {
      val map = LinkedHashMap<String, String>(7)
      map.put("teamcity-build-number", System.getProperty("build.number"))
      map.put("teamcity-build-type-id", System.getProperty("teamcity.buildType.id"))
      map.put("teamcity-build-configuration-name", System.getenv("TEAMCITY_BUILDCONF_NAME"))
      map.put("teamcity-build-project-name", System.getenv("TEAMCITY_PROJECT_NAME"))
      map.put("branch", System.getProperty("teamcity.build.branch")?.takeIf(String::isNotEmpty) ?: "master")
      map.put("project", System.getProperty("intellij.test.discovery.project")?.takeIf(String::isNotEmpty) ?: "intellij")
      map.put("checkout-root-prefix", System.getProperty("intellij.build.test.discovery.checkout.root.prefix"))
      uploader.upload(Path.of(file), map)
    }
    catch (e: Exception) {
      messages.error(e.message!!, e)
    }
  }
  messages.buildStatus("With Discovery, {build.status.text}")
}

private const val NO_TESTS_ERROR = 42
private val whitespaceRegex = Regex("\\s+")
