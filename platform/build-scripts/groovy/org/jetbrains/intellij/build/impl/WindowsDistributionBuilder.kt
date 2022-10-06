// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet")

package org.jetbrains.intellij.build.impl

import com.intellij.diagnostic.telemetry.createTask
import com.intellij.diagnostic.telemetry.useWithScope
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.util.text.StringUtilRt
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import org.jdom.Element
import org.jetbrains.intellij.build.*
import org.jetbrains.intellij.build.TraceManager.spanBuilder
import org.jetbrains.intellij.build.impl.productInfo.*
import org.jetbrains.intellij.build.impl.support.RepairUtilityBuilder
import org.jetbrains.intellij.build.io.copyFileToDir
import org.jetbrains.intellij.build.io.runProcess
import org.jetbrains.intellij.build.io.substituteTemplatePlaceholders
import org.jetbrains.intellij.build.io.transformFile
import org.jetbrains.jps.model.library.JpsOrderRootType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ForkJoinTask
import java.util.function.BiPredicate

internal class WindowsDistributionBuilder(
  private val context: BuildContext,
  private val customizer: WindowsDistributionCustomizer,
  private val ideaProperties: Path?,
  private val patchedApplicationInfo: String,
) : OsSpecificDistributionBuilder {
  private val icoFile: Path?

  init {
    val icoPath = (if (context.applicationInfo.isEAP) customizer.icoPathForEAP else null) ?: customizer.icoPath
    icoFile = icoPath?.let { Path.of(icoPath) }
  }

  override val targetOs: OsFamily
    get() = OsFamily.WINDOWS

  override fun copyFilesForOsDistribution(targetPath: Path, arch: JvmArchitecture) {
    val distBinDir = targetPath.resolve("bin")
    Files.createDirectories(distBinDir)

    val binWin = FileSet(context.paths.communityHomeDir.resolve("bin/win")).includeAll()
    if (!context.includeBreakGenLibraries()) {
      @Suppress("SpellCheckingInspection")
      binWin.exclude("breakgen*")
    }
    binWin.copyToDir(distBinDir)

    val pty4jNativeDir = unpackPty4jNative(context, targetPath, "win")
    generateBuildTxt(context, targetPath)
    copyDistFiles(context, targetPath)

    Files.writeString(distBinDir.resolve(ideaProperties!!.fileName), StringUtilRt.convertLineSeparators(Files.readString(ideaProperties), "\r\n"))

    if (icoFile != null) {
      Files.copy(icoFile, distBinDir.resolve("${context.productProperties.baseFileName}.ico"), StandardCopyOption.REPLACE_EXISTING)
    }
    if (customizer.includeBatchLaunchers) {
      generateScripts(distBinDir)
    }
    generateVMOptions(distBinDir)

    // ROMOLO EDIT: replaced with custom launcher generator
    buildRomoloWinLauncher(targetPath)

    customizer.copyAdditionalFiles(context, targetPath.toString())

    context.executeStep(spanBuilder = spanBuilder("sign windows"), stepId = BuildOptions.WIN_SIGN_STEP) {
      val nativeFiles = ArrayList<Path>()
      for (nativeRoot in listOf(distBinDir, pty4jNativeDir)) {
        Files.find(nativeRoot, Integer.MAX_VALUE, BiPredicate { file, attributes ->
          if (attributes.isRegularFile) {
            val path = file.toString()
            path.endsWith(".exe") || path.endsWith(".dll")
          }
          else {
            false
          }
        }).use { stream ->
          stream.forEach(nativeFiles::add)
        }
      }

      Span.current().setAttribute(AttributeKey.stringArrayKey("files"), nativeFiles.map(Path::toString))
      customizer.getBinariesToSign(context).mapTo(nativeFiles) { targetPath.resolve(it) }
      if (nativeFiles.isNotEmpty()) {
        context.signFiles(nativeFiles, BuildOptions.WIN_SIGN_OPTIONS)
      }
    }
  }

  override fun buildArtifacts(osAndArchSpecificDistPath: Path, arch: JvmArchitecture) {
    copyFilesForOsDistribution(osAndArchSpecificDistPath, arch)

    val jreDir = context.bundledRuntime.extract(BundledRuntimeImpl.getProductPrefix(context), OsFamily.WINDOWS, arch)

    @Suppress("SpellCheckingInspection")
    val vcRtDll = jreDir.resolve("jbr/bin/msvcp140.dll")
    check(Files.exists(vcRtDll)) {
      "VS C++ Runtime DLL (${vcRtDll.fileName}) not found in ${vcRtDll.parent}.\n" +
      "If JBR uses a newer version, please correct the path in this code and update Windows Launcher build configuration.\n" +
      "If DLL was relocated to another place, please correct the path in this code."
    }

    copyFileToDir(vcRtDll, osAndArchSpecificDistPath.resolve("bin"))

    val zipPathTask = if (customizer.buildZipArchive) {
      val jreDirectoryPaths = if (customizer.zipArchiveWithBundledJre) listOf(jreDir) else emptyList()
      createBuildWinZipTask(jreDirectoryPaths, ".win", osAndArchSpecificDistPath, customizer, context).fork()
    }
    else {
      null
    }

    var exePath: Path? = null
    context.executeStep("build Windows Exe Installer", BuildOptions.WINDOWS_EXE_INSTALLER_STEP) {
      val productJsonDir = context.paths.tempDir.resolve("win.dist.product-info.json.exe")
      validateProductJson(jsonText = generateProductJson(targetDir = productJsonDir, isJreIncluded = true, context = context),
                          relativePathToProductJson = "",
                          installationDirectories = listOf(context.paths.distAllDir, osAndArchSpecificDistPath, jreDir),
                          installationArchives = emptyList(),
                          context = context)

      exePath = WinExeInstallerBuilder(context, customizer, jreDir)
        .buildInstaller(osAndArchSpecificDistPath, productJsonDir, "", context)
    }

    val zipPath = zipPathTask?.join()
    if (context.options.isInDevelopmentMode || zipPath == null || exePath == null) {
      return
    }

    if (!SystemInfoRt.isLinux) {
      Span.current().addEvent("comparing .zip and .exe is not supported on ${SystemInfoRt.OS_NAME}")
      return
    }

    Span.current().addEvent("compare ${zipPath.fileName} vs. ${exePath!!.fileName}")

    val tempZip = Files.createTempDirectory(context.paths.tempDir, "zip-")
    val tempExe = Files.createTempDirectory(context.paths.tempDir, "exe-")
    try {
      runProcess(args = listOf("7z", "x", "-bd", exePath.toString()), workingDir = tempExe, logger = context.messages)
      runProcess(args = listOf("unzip", "-q", zipPath.toString()), workingDir = tempZip, logger = context.messages)
      @Suppress("SpellCheckingInspection")
      NioFiles.deleteRecursively(tempExe.resolve("\$PLUGINSDIR"))

      runProcess(listOf("diff", "-q", "-r", tempZip.toString(), tempExe.toString()), null, context.messages)
      if (!context.options.buildStepsToSkip.contains(BuildOptions.REPAIR_UTILITY_BUNDLE_STEP)) {
        RepairUtilityBuilder.generateManifest(context, tempExe, OsFamily.WINDOWS, arch)
      }
    }
    finally {
      NioFiles.deleteRecursively(tempZip)
      NioFiles.deleteRecursively(tempExe)
    }
  }

  private fun generateScripts(distBinDir: Path) {
    val fullName = context.applicationInfo.productName
    val baseName = context.productProperties.baseFileName
    val scriptName = "${baseName}.bat"
    val vmOptionsFileName = "${baseName}64.exe"

    val classPathJars = context.bootClassPathJarNames
    var classPath = "SET \"CLASS_PATH=%IDE_HOME%\\lib\\${classPathJars.get(0)}\""
    for (i in 1 until  classPathJars.size) {
      classPath += "\nSET \"CLASS_PATH=%CLASS_PATH%;%IDE_HOME%\\lib\\${classPathJars.get(i)}\""
    }

    var additionalJvmArguments = context.getAdditionalJvmArguments(OsFamily.WINDOWS)
    if (!context.xBootClassPathJarNames.isEmpty()) {
      additionalJvmArguments = additionalJvmArguments.toMutableList()
      val bootCp = context.xBootClassPathJarNames.joinToString(separator = ";") { "%IDE_HOME%\\lib\\${it}" }
      additionalJvmArguments.add("\"-Xbootclasspath/a:$bootCp\"")
    }

    val winScripts = context.paths.communityHomeDir.resolve("platform/build-scripts/resources/win/scripts")
    val actualScriptNames = Files.newDirectoryStream(winScripts).use { dirStream -> dirStream.map { it.fileName.toString() }.sorted() }
    @Suppress("SpellCheckingInspection")
    val expectedScriptNames = listOf("executable-template.bat", "format.bat", "inspect.bat", "ltedit.bat")
    check(actualScriptNames == expectedScriptNames) {
      "Expected script names '${expectedScriptNames.joinToString(separator = " ")}', " +
      "but got '${actualScriptNames.joinToString(separator = " ")}' " +
      "in $winScripts. Please review ${WindowsDistributionBuilder::class.java.name} and update accordingly"
    }

    substituteTemplatePlaceholders(
      inputFile = winScripts.resolve("executable-template.bat"),
      outputFile = distBinDir.resolve(scriptName),
      placeholder = "@@",
      values = listOf(
        Pair("product_full", fullName),
        Pair("product_uc", context.productProperties.getEnvironmentVariableBaseName(context.applicationInfo)),
        Pair("product_vendor", context.applicationInfo.shortCompanyName),
        Pair("vm_options", vmOptionsFileName),
        Pair("system_selector", context.systemSelector),
        Pair("ide_jvm_args", additionalJvmArguments.joinToString(separator = " ")),
        Pair("class_path", classPath),
        Pair("base_name", baseName),
      )
    )

    val inspectScript = context.productProperties.inspectCommandName
    @Suppress("SpellCheckingInspection")
    for (fileName in listOf("format.bat", "inspect.bat", "ltedit.bat")) {
      val sourceFile = winScripts.resolve(fileName)
      val targetFile = distBinDir.resolve(fileName)

      substituteTemplatePlaceholders(
        inputFile = sourceFile,
        outputFile = targetFile,
        placeholder = "@@",
        values = listOf(
          Pair("product_full", fullName),
          Pair("script_name", scriptName),
        )
      )
    }

    if (inspectScript != "inspect") {
      val targetPath = distBinDir.resolve("${inspectScript}.bat")
      Files.move(distBinDir.resolve("inspect.bat"), targetPath)
      context.patchInspectScript(targetPath)
    }

    FileSet(distBinDir)
      .include("*.bat")
      .enumerate()
      .forEach { file ->
        transformFile(file) { target ->
          Files.writeString(target, toDosLineEndings(Files.readString(file)))
        }
      }
  }

  private fun generateVMOptions(distBinDir: Path) {
    val productProperties = context.productProperties
    val fileName = "${productProperties.baseFileName}64.exe.vmoptions"
    val vmOptions = VmOptionsGenerator.computeVmOptions(context.applicationInfo.isEAP, productProperties)
    VmOptionsGenerator.writeVmOptions(distBinDir.resolve(fileName), vmOptions, "\r\n")
  }

  // ROMOLO EDIT: Replaced with buildRomoloWinLauncher
  private fun buildWinLauncher(winDistPath: Path) {
    spanBuilder("build Windows executable").useWithScope {
      val executableBaseName = "${context.productProperties.baseFileName}64"
      val launcherPropertiesPath = context.paths.tempDir.resolve("launcher.properties")
      val upperCaseProductName = context.applicationInfo.upperCaseProductName
      @Suppress("SpellCheckingInspection")
      val vmOptions = context.getAdditionalJvmArguments(OsFamily.WINDOWS) + listOf("-Dide.native.launcher=true")
      val productName = context.applicationInfo.shortProductName
      val classPath = context.bootClassPathJarNames.joinToString(separator = ";")
      val bootClassPath = context.xBootClassPathJarNames.joinToString(separator = ";")
      val envVarBaseName = context.productProperties.getEnvironmentVariableBaseName(context.applicationInfo)
      val icoFilesDirectory = context.paths.tempDir.resolve("win-launcher-ico")
      val appInfoForLauncher = generateApplicationInfoForLauncher(patchedApplicationInfo, icoFilesDirectory)
      @Suppress("SpellCheckingInspection")
      Files.writeString(launcherPropertiesPath, """
        IDS_JDK_ONLY=${context.productProperties.toolsJarRequired}
        IDS_JDK_ENV_VAR=${envVarBaseName}_JDK
        IDS_APP_TITLE=$productName Launcher
        IDS_VM_OPTIONS_PATH=%APPDATA%\\\\${context.applicationInfo.shortCompanyName}\\\\${context.systemSelector}
        IDS_VM_OPTION_ERRORFILE=-XX:ErrorFile=%USERPROFILE%\\\\java_error_in_${executableBaseName}_%p.log
        IDS_VM_OPTION_HEAPDUMPPATH=-XX:HeapDumpPath=%USERPROFILE%\\\\java_error_in_${executableBaseName}.hprof
        IDC_WINLAUNCHER=${upperCaseProductName}_LAUNCHER
        IDS_PROPS_ENV_VAR=${envVarBaseName}_PROPERTIES
        IDS_VM_OPTIONS_ENV_VAR=${envVarBaseName}_VM_OPTIONS
        IDS_ERROR_LAUNCHING_APP=Error launching $productName
        IDS_VM_OPTIONS=${vmOptions.joinToString(separator = " ")}
        IDS_CLASSPATH_LIBS=${classPath}
        IDS_BOOTCLASSPATH_LIBS=${bootClassPath}
        """.trimIndent().trim())

      val communityHome = context.paths.communityHome
      val inputPath = "${communityHome}/platform/build-scripts/resources/win/launcher/WinLauncher.exe"
      val outputPath = winDistPath.resolve("bin/${executableBaseName}.exe")
      val classpath = ArrayList<String>()
      classpath.add("$communityHome/build/lib/launcher-generator.jar")
      listOf("Guava", "commons-imaging").forEach {
        for (file in context.project.libraryCollection.findLibrary(it)!!.getPaths(JpsOrderRootType.COMPILED)) {
          classpath.add(file.toString())
        }
      }

      sequenceOf(context.findApplicationInfoModule(), context.findRequiredModule("intellij.platform.icons"))
        .flatMap { it.sourceRoots }
        .forEach { root ->
          classpath.add(root.file.absolutePath)
        }

      for (p in context.productProperties.brandingResourcePaths) {
        classpath.add(p.toString())
      }
      classpath.add(icoFilesDirectory.toString())
      classpath.add(context.getModuleOutputDir(context.findRequiredModule("intellij.platform.util.jdom")).toString())

      runJava(
        context = context,
        mainClass = "com.pme.launcher.LauncherGeneratorMain",
        args = listOf(
          inputPath,
          appInfoForLauncher.toString(),
          "$communityHome/native/WinLauncher/resource.h",
          launcherPropertiesPath.toString(),
          outputPath.toString(),
        ),
        jvmArgs = listOf("-Djava.awt.headless=true"),
        classPath = classpath
      )
    }
  }

  private fun buildRomoloWinLauncher(winDistPath: Path) {
    spanBuilder("build Romolo Windows executable").useWithScope {
      val executableBaseName = context.productProperties.baseFileName
      val launcherPropertiesPath = context.paths.tempDir.resolve("launcher.properties")
      @Suppress("SpellCheckingInspection")
      val vmOptions = context.getAdditionalJvmArguments(OsFamily.WINDOWS) + listOf("-Dide.native.launcher=true")
      val productName = context.applicationInfo.shortProductName
      val classPath = context.bootClassPathJarNames.joinToString(separator = ";")
      val bootClassPath = context.xBootClassPathJarNames.joinToString(separator = ";")
      val envVarBaseName = context.productProperties.getEnvironmentVariableBaseName(context.applicationInfo)
      val icoFilesDirectory = context.paths.tempDir.resolve("win-launcher-ico")
      val appInfoForLauncher = generateApplicationInfoForLauncher(patchedApplicationInfo, icoFilesDirectory)

      val jbrPath = "packages/jbr-17.0.3"

      @Suppress("SpellCheckingInspection")
      Files.writeString(launcherPropertiesPath, """
        IDS_JDK_ENV_VAR=${envVarBaseName}_JDK
        IDS_APP_TITLE=$productName Launcher
        IDS_VM_OPTIONS_PATH=%APPDATA%\\\\${context.applicationInfo.shortCompanyName}\\\\${context.systemSelector}
        IDS_VM_OPTION_ERRORFILE=-XX:ErrorFile=%USERPROFILE%\\\\java_error_in_${executableBaseName}_%p.log
        IDS_VM_OPTION_HEAPDUMPPATH=-XX:HeapDumpPath=%USERPROFILE%\\\\java_error_in_${executableBaseName}.hprof
        IDS_PROPS_ENV_VAR=${envVarBaseName}_PROPERTIES
        IDS_VM_OPTIONS_ENV_VAR=${envVarBaseName}_VM_OPTIONS
        IDS_VM_OPTIONS=${vmOptions.joinToString(separator = " ")}
        IDS_CLASSPATH_LIBS=${classPath}
        IDS_BOOTCLASSPATH_LIBS=${bootClassPath}
        IDS_JBR_PATH=${jbrPath}
        IDS_ROOT_APPLICATION_PATH=${context.getApplicationFolderName()}
        """.trimIndent().trim())

      val inputPath = "${context.paths.projectHome}/bin/win/romolo.launcher.exe"
      val outputPath = winDistPath.resolve("${executableBaseName}.exe")

      //LauncherGeneratorMain.main(arrayOf(
      //  inputPath,
      //  appInfoForLauncher.toString(),
      //  "${context.paths.projectHome}/natives/tools/launcher/windows/resource.h",
      //  launcherPropertiesPath.toString(),
      //  outputPath.toString(),
      //))
    }
  }

  /**
   * Generates ApplicationInfo.xml file for launcher generator which contains link to proper *.ico file.
   * todo pass path to ico file to LauncherGeneratorMain directly (probably after IDEA-196705 is fixed).
   */
  private fun generateApplicationInfoForLauncher(appInfo: String, icoFilesDirectory: Path): Path {
    val patchedFile = context.paths.tempDir.resolve("win-launcher-application-info.xml")
    if (icoFile == null) {
      Files.writeString(patchedFile, appInfo)
      return patchedFile
    }

    Files.createDirectories(icoFilesDirectory)
    Files.copy(icoFile, icoFilesDirectory.resolve(icoFile.fileName), StandardCopyOption.REPLACE_EXISTING)
    val root = JDOMUtil.load(appInfo)
    // do not use getChild - maybe null due to namespace
    val iconElement = root.content.firstOrNull { it is Element && it.name == "icon" }
                      ?: throw RuntimeException("`icon` element not found in $appInfo:\n${appInfo}")

    (iconElement as Element).setAttribute("ico", icoFile.fileName.toString())
    JDOMUtil.write(root, patchedFile)
    return patchedFile
  }
}

private fun createBuildWinZipTask(jreDirectoryPaths: List<Path>,
                                  @Suppress("SameParameterValue") zipNameSuffix: String,
                                  winDistPath: Path,
                                  customizer: WindowsDistributionCustomizer,
                                  context: BuildContext): ForkJoinTask<Path> {
  val baseName = context.productProperties.getBaseArtifactName(context.applicationInfo, context.buildNumber)
  val targetFile = context.paths.artifactDir.resolve("${baseName}${zipNameSuffix}.zip")
  return createTask(spanBuilder("build Windows ${zipNameSuffix}.zip distribution")
                      .setAttribute("targetFile", targetFile.toString())) {
    val productJsonDir = context.paths.tempDir.resolve("win.dist.product-info.json.zip$zipNameSuffix")
    generateProductJson(productJsonDir, !jreDirectoryPaths.isEmpty(), context)

    val zipPrefix = customizer.getRootDirectoryName(context.applicationInfo, context.buildNumber)
    val dirs = listOf(context.paths.distAllDir, winDistPath, productJsonDir) + jreDirectoryPaths
    zipWithPrefixes(context = context,
                    targetFile = targetFile,
                    map = dirs.associateWithTo(LinkedHashMap(dirs.size)) { zipPrefix },
                    compress = true)
    checkInArchive(context = context, archiveFile = targetFile, pathInArchive = zipPrefix)
    context.notifyArtifactWasBuilt(targetFile)
    targetFile
  }
}

private fun generateProductJson(targetDir: Path, isJreIncluded: Boolean, context: BuildContext): String {
  val launcherPath = "bin/${context.productProperties.baseFileName}64.exe"
  val vmOptionsPath = "bin/${context.productProperties.baseFileName}64.exe.vmoptions"
  val javaExecutablePath = if (isJreIncluded) "jbr/bin/java.exe" else null

  val file = targetDir.resolve(PRODUCT_INFO_FILE_NAME)
  Files.createDirectories(targetDir)

  val json = generateMultiPlatformProductJson(
    "bin",
    context.getBuiltinModule(),
    listOf(
      ProductInfoLaunchData(
        os = OsFamily.WINDOWS.osName,
        launcherPath = launcherPath,
        javaExecutablePath = javaExecutablePath,
        vmOptionsFilePath = vmOptionsPath,
        startupWmClass = null,
      )
    ), context)
  Files.writeString(file, json)
  return json
}

private fun toDosLineEndings(x: String): String {
  return x.replace("\r", "").replace("\n", "\r\n")
}
