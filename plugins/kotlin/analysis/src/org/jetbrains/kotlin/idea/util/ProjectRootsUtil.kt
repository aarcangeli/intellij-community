// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.util

import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.ide.scratch.ScratchUtil
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModulePointerManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.FileIndex
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInFileType
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinModuleFileType
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.decompiler.js.KotlinJavaScriptMetaFileType
import org.jetbrains.kotlin.idea.klib.KlibMetaFileType
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.ide

abstract class KotlinBinaryExtension(val fileType: FileType) {
    companion object {
        val EP_NAME: ExtensionPointName<KotlinBinaryExtension> =
            ExtensionPointName.create<KotlinBinaryExtension>("org.jetbrains.kotlin.binaryExtension")

        val kotlinBinaries: List<FileType> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            EP_NAME.extensions.map { it.fileType }
        }
    }
}

class JavaClassBinary : KotlinBinaryExtension(JavaClassFileType.INSTANCE)
class KotlinBuiltInBinary : KotlinBinaryExtension(KotlinBuiltInFileType)
class KotlinModuleBinary : KotlinBinaryExtension(KotlinModuleFileType.INSTANCE)
class KotlinJsMetaBinary : KotlinBinaryExtension(KotlinJavaScriptMetaFileType)
class KlibMetaBinary : KotlinBinaryExtension(KlibMetaFileType)

fun FileType.isKotlinBinary(): Boolean = this in KotlinBinaryExtension.kotlinBinaries

fun FileIndex.isInSourceContentWithoutInjected(file: VirtualFile): Boolean {
    return file !is VirtualFileWindow && isInSourceContent(file)
}

fun VirtualFile.getSourceRoot(project: Project): VirtualFile? = ProjectRootManager.getInstance(project).fileIndex.getSourceRootForFile(this)

val PsiFileSystemItem.sourceRoot: VirtualFile?
    get() = virtualFile?.getSourceRoot(project)

object ProjectRootsUtil {

    private fun List<ScriptAcceptedLocation>.containsAllowedLocations() =
        contains(ScriptAcceptedLocation.Everywhere) || contains(ScriptAcceptedLocation.Project)

    @Suppress("DEPRECATION")
    @JvmStatic
    fun isInContent(
        project: Project,
        file: VirtualFile,
        includeProjectSource: Boolean,
        includeLibrarySource: Boolean,
        includeLibraryClasses: Boolean,
        includeScriptDependencies: Boolean,
        includeScriptsOutsideSourceRoots: Boolean,
        fileIndex: ProjectFileIndex = ProjectFileIndex.getInstance(project)
    ): Boolean {
        ProgressManager.checkCanceled()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.nameSequence)
        val kotlinExcludeLibrarySources = fileType == KotlinFileType.INSTANCE && !includeLibrarySource && !includeScriptsOutsideSourceRoots
        if (kotlinExcludeLibrarySources && !includeProjectSource) return false

        if (fileIndex.isInSourceContentWithoutInjected(file)) return includeProjectSource

        if (kotlinExcludeLibrarySources) return false

        val scriptDefinition = file.findScriptDefinition(project)
        val scriptScope: List<ScriptAcceptedLocation>? = scriptDefinition?.compilationConfiguration?.get(ScriptCompilationConfiguration.ide.acceptedLocations)
        if (scriptScope != null) {
            val includeAll = scriptScope.containsAllowedLocations() || ScratchUtil.isScratch(file)
            val includeAllOrScriptLibraries = includeAll || scriptScope.contains(ScriptAcceptedLocation.Libraries)
            return isInContentWithoutScriptDefinitionCheck(
                project,
                file,
                fileType,
                includeProjectSource && (
                        includeAll
                                || scriptScope.contains(ScriptAcceptedLocation.Sources)
                                || scriptScope.contains(ScriptAcceptedLocation.Tests)
                        ),
                includeLibrarySource && includeAllOrScriptLibraries,
                includeLibraryClasses && includeAllOrScriptLibraries,
                includeScriptDependencies && includeAllOrScriptLibraries,
                includeScriptsOutsideSourceRoots && includeAll,
                fileIndex
            )
        }
        return isInContentWithoutScriptDefinitionCheck(
            project,
            file,
            fileType,
            includeProjectSource,
            includeLibrarySource,
            includeLibraryClasses,
            includeScriptDependencies,
            false,
            fileIndex
        )
    }

    @Suppress("DEPRECATION")
    private fun isInContentWithoutScriptDefinitionCheck(
        project: Project,
        file: VirtualFile,
        fileType: FileType,
        includeProjectSource: Boolean,
        includeLibrarySource: Boolean,
        includeLibraryClasses: Boolean,
        includeScriptDependencies: Boolean,
        includeScriptsOutsideSourceRoots: Boolean,
        fileIndex: ProjectFileIndex = ProjectFileIndex.getInstance(project)
    ): Boolean {
        if (includeScriptsOutsideSourceRoots) {
            if (ProjectRootManager.getInstance(project).fileIndex.isInContent(file) || ScratchUtil.isScratch(file)) {
                return true
            }
            return file.findScriptDefinition(project)
                ?.compilationConfiguration
                ?.get(ScriptCompilationConfiguration.ide.acceptedLocations)?.containsAllowedLocations() == true
        }

        if (!includeLibraryClasses && !includeLibrarySource) return false

        // NOTE: the following is a workaround for cases when class files are under library source roots and source files are under class roots
        val canContainClassFiles = fileType == ArchiveFileType.INSTANCE || file.isDirectory
        val isBinary = fileType.isKotlinBinary()

        val scriptConfigurationManager = if (includeScriptDependencies) ScriptConfigurationManager.getInstance(project) else null

        if (includeLibraryClasses && (isBinary || canContainClassFiles)) {
            if (fileIndex.isInLibraryClasses(file)) return true
            if (scriptConfigurationManager?.getAllScriptsDependenciesClassFilesScope()?.contains(file) == true) return true
        }
        if (includeLibrarySource && !isBinary) {
            if (fileIndex.isInLibrarySource(file)) return true
            if (scriptConfigurationManager?.getAllScriptDependenciesSourcesScope()?.contains(file) == true &&
                !fileIndex.isInSourceContentWithoutInjected(file)
            ) {
                return true
            }
        }

        return false
    }

    @JvmStatic
    fun isInContent(
        element: PsiElement,
        includeProjectSource: Boolean,
        includeLibrarySource: Boolean,
        includeLibraryClasses: Boolean,
        includeScriptDependencies: Boolean,
        includeScriptsOutsideSourceRoots: Boolean
    ): Boolean = runReadAction {
        val virtualFile = when (element) {
            is PsiDirectory -> element.virtualFile
            else -> element.containingFile?.virtualFile
        } ?: return@runReadAction false

        val project = element.project
        return@runReadAction isInContent(
            project,
            virtualFile,
            includeProjectSource,
            includeLibrarySource,
            includeLibraryClasses,
            includeScriptDependencies,
            includeScriptsOutsideSourceRoots
        )
    }

    @JvmOverloads
    @JvmStatic
    fun isInProjectSource(element: PsiElement, includeScriptsOutsideSourceRoots: Boolean = false): Boolean {
        return isInContent(
            element,
            includeProjectSource = true,
            includeLibrarySource = false,
            includeLibraryClasses = false,
            includeScriptDependencies = false,
            includeScriptsOutsideSourceRoots = includeScriptsOutsideSourceRoots
        )
    }

    @JvmOverloads
    @JvmStatic
    fun isProjectSourceFile(project: Project, file: VirtualFile, includeScriptsOutsideSourceRoots: Boolean = false): Boolean {
        return isInContent(
            project,
            file,
            includeProjectSource = true,
            includeLibrarySource = false,
            includeLibraryClasses = false,
            includeScriptDependencies = false,
            includeScriptsOutsideSourceRoots = includeScriptsOutsideSourceRoots
        )
    }

    @JvmOverloads
    @JvmStatic
    fun isInProjectOrLibSource(element: PsiElement, includeScriptsOutsideSourceRoots: Boolean = false): Boolean {
        return isInContent(
            element,
            includeProjectSource = true,
            includeLibrarySource = true,
            includeLibraryClasses = false,
            includeScriptDependencies = false,
            includeScriptsOutsideSourceRoots = includeScriptsOutsideSourceRoots
        )
    }

    @JvmStatic
    fun isInProjectOrLibraryContent(element: PsiElement): Boolean {
        return isInContent(
            element,
            includeProjectSource = true,
            includeLibrarySource = true,
            includeLibraryClasses = true,
            includeScriptDependencies = true,
            includeScriptsOutsideSourceRoots = false
        )
    }

    @JvmStatic
    fun isInProjectOrLibraryClassFile(element: PsiElement): Boolean {
        return isInContent(
            element,
            includeProjectSource = true,
            includeLibrarySource = false,
            includeLibraryClasses = true,
            includeScriptDependencies = false,
            includeScriptsOutsideSourceRoots = false
        )
    }

    @JvmStatic
    fun isLibraryClassFile(project: Project, file: VirtualFile): Boolean {
        return isInContent(
            project,
            file,
            includeProjectSource = false,
            includeLibrarySource = false,
            includeLibraryClasses = true,
            includeScriptDependencies = true,
            includeScriptsOutsideSourceRoots = false
        )
    }

    @JvmStatic
    fun isLibrarySourceFile(project: Project, file: VirtualFile): Boolean {
        return isInContent(
            project,
            file,
            includeProjectSource = false,
            includeLibrarySource = true,
            includeLibraryClasses = false,
            includeScriptDependencies = true,
            includeScriptsOutsideSourceRoots = false
        )
    }

    @JvmStatic
    fun isLibraryFile(project: Project, file: VirtualFile): Boolean {
        return isInContent(
            project,
            file,
            includeProjectSource = false,
            includeLibrarySource = true,
            includeLibraryClasses = true,
            includeScriptDependencies = true,
            includeScriptsOutsideSourceRoots = false
        )
    }
}

val Module.rootManager: ModuleRootManager
    get() = ModuleRootManager.getInstance(this)

val Module.sourceRoots: Array<VirtualFile>
    get() = rootManager.sourceRoots

val PsiElement.module: Module?
    get() = ModuleUtilCore.findModuleForPsiElement(this)

fun VirtualFile.findModule(project: Project) = ModuleUtilCore.findModuleForFile(this, project)

fun Module.createPointer() =
    ModulePointerManager.getInstance(project).create(this)