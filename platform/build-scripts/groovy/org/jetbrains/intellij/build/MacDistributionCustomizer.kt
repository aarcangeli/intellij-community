// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.intellij.build


import org.jetbrains.intellij.build.impl.support.RepairUtilityBuilder
import java.nio.file.Path
import java.util.function.Predicate

abstract class MacDistributionCustomizer {
  /**
   * Path to icns file containing product icon bundle for macOS distribution
   * For full description of icns files see <a href="https://en.wikipedia.org/wiki/Apple_Icon_Image_format">Apple Icon Image Format</a>
   */
  lateinit var icnsPath: String

  /**
   * Path to icns file for EAP builds (if {@code null} {@link #icnsPath} will be used)
   */
  var icnsPathForEAP: String? = null

  /**
   * An unique identifier string that specifies the app type of the bundle. The string should be in reverse DNS format using only the Roman alphabet in upper and lower case (A-Z, a-z), the dot ("."), and the hyphen ("-")
   * See <a href="https://developer.apple.com/library/ios/documentation/General/Reference/InfoPlistKeyReference/Articles/CoreFoundationKeys.html#//apple_ref/doc/uid/20001431-102070">CFBundleIdentifier</a> for details
   */
  lateinit var bundleIdentifier: String

  /**
   * Path to an image which will be injected into .dmg file
   */
  lateinit var dmgImagePath: String

  /**
   * The minimum version of macOS where the product is allowed to be installed
   */
  var minOSXVersion = "10.8"

  /**
   * String with declarations of additional file types that should be automatically opened by the application.
   * Example:
   * <pre>
   * &lt;dict&gt;
   *   &lt;key&gt;CFBundleTypeExtensions&lt;/key&gt;
   *   &lt;array&gt;
   *     &lt;string&gt;extension&lt;/string&gt;
   *   &lt;/array&gt;
   *   &lt;key&gt;CFBundleTypeIconFile&lt;/key&gt;
   *   &lt;string&gt;path_to_icons.icns&lt;/string&gt;
   *   &lt;key&gt;CFBundleTypeName&lt;/key&gt;
   *   &lt;string&gt;File type description&lt;/string&gt;
   *   &lt;key&gt;CFBundleTypeRole&lt;/key&gt;
   *   &lt;string&gt;Editor&lt;/string&gt;
   * &lt;/dict&gt;
   * </pre>
   */
  var additionalDocTypes = ""

  /**
   * Note that users won't be able to switch off some of these associations during installation
   * so include only types of files which users will definitely prefer to open by the product.
   *
   * @see FileAssociation
   */
  var fileAssociations: MutableList<FileAssociation> = mutableListOf()

  /**
   * Specify &lt;scheme&gt; here if you want product to be able to open urls like <scheme>://open?file=/some/file/path&line=0
   */
  var urlSchemes: MutableList<String> = mutableListOf()

  /**
   * CPU architectures app can be launched on, currently arm64 and x86_64 are supported
   */
  var architectures: MutableList<String> = mutableListOf("arm64", "x86_64")

  /**
   * If {@code true} *.ipr files will be associated with the product in Info.plist
   */
  var associateIpr = false


  /**
   * Relative paths to files in macOS distribution which should take 'executable' permissions
   */
  var extraExecutables: MutableList<String> = mutableListOf()

  /**
   * Filter for files that is going to be put to `<distribution>/bin` directory.
   */
  var binFilesFilter: Predicate<Path> = Predicate { true }

  /**
   * Relative paths to files in macOS distribution which should be signed
   */
  open fun getBinariesToSign(context: BuildContext, arch: JvmArchitecture): List<String> = listOf()

  /**
   * Path to a image which will be injected into .dmg file for EAP builds (if {@code null} dmgImagePath will be used)
   */
  var dmgImagePathForEAP: String? = null

  /**
   * If {@code true} will publish sit archive as artifact.
   *
   * `true` by default because archive is required for patches.
   */
  var publishArchive = true

  /**
   * Application bundle name: &lt;name&gt;.app. Current convention is to have ProductName.app for release and ProductName Version EAP.app.
   * @param applicationInfo application info that can be used to check for EAP and building version
   * @param buildNumber current build number
   * @return application bundle directory name
   */
  open fun getRootDirectoryName(applicationInfo: ApplicationInfoProperties, buildNumber: String): String {
    val suffix = if (applicationInfo.isEAP) " ${applicationInfo.majorVersion}.${applicationInfo.minorVersionMainPart} EAP" else ""
    return "${applicationInfo.productName}${suffix}.app"
  }

  /**
   * Custom properties to be added to the properties file. They will be used for launched product, e.g. you can add additional logging in EAP builds
   * @param applicationInfo application info that can be used to check for EAP and building version
   * @return map propertyName-&gt;propertyValue
   */
  open fun getCustomIdeaProperties(applicationInfo: ApplicationInfoProperties): Map<String, String> = emptyMap()

  /**
   * Additional files to be copied to the distribution, e.g. help bundle or debugger binaries
   *
   * @param context build context that contains information about build directories, product properties and application info
   * @param targetDirectory application bundle directory
   */
  open fun copyAdditionalFiles(context: BuildContext, targetDirectory: String) {
  }

  /**
   * Additional files to be copied to the distribution with specific architecture, e.g. help bundle or debugger binaries
   *
   * Method is invoked after {@link #copyAdditionalFiles(org.jetbrains.intellij.build.BuildContext, java.lang.String)}.
   * In this method invocation {@code targetDirectory} may be different then in aforementioned method and may contain nothing.
   *
   * @param context build context that contains information about build directories, product properties and application info
   * @param targetDirectory application bundle directory
   * @param arch distribution target architecture, not null
   */
  open fun copyAdditionalFiles(context: BuildContext, targetDirectory: Path, arch: JvmArchitecture) {
    RepairUtilityBuilder.bundle(context, OsFamily.MACOS, arch, targetDirectory)
  }
}
