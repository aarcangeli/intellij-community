// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.caches.project

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.analysis.decompiled.light.classes.KtLightClassForDecompiledDeclaration
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.idea.core.isInTestSourceContentKotlinAware
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.core.script.ScriptRelatedModuleNameFile
import org.jetbrains.kotlin.idea.highlighter.OutsidersPsiFileSupportUtils
import org.jetbrains.kotlin.idea.util.ProjectRootsUtil
import org.jetbrains.kotlin.idea.util.isInSourceContentWithoutInjected
import org.jetbrains.kotlin.idea.util.isKotlinBinary
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.runReadAction
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.sure
import org.jetbrains.kotlin.utils.yieldIfNotNull

var PsiFile.forcedModuleInfo: ModuleInfo? by UserDataProperty(Key.create("FORCED_MODULE_INFO"))

@JvmOverloads
fun PsiElement.getModuleInfo(createSourceLibraryInfoForLibraryBinaries: Boolean = true): IdeaModuleInfo =
    this.collectInfos(ModuleInfoCollector.NotNullTakeFirst, createSourceLibraryInfoForLibraryBinaries)

fun PsiElement.getNullableModuleInfo(): IdeaModuleInfo? = this.collectInfos(ModuleInfoCollector.NullableTakeFirst)

fun PsiElement.getModuleInfos(): Sequence<IdeaModuleInfo> = this.collectInfos(ModuleInfoCollector.ToSequence)

fun ModuleInfo.findSdkAcrossDependencies(): SdkInfo? {
    val project = (this as? IdeaModuleInfo)?.project ?: return null

    return SdkInfoCache.getInstance(project).findOrGetCachedSdk(this)
}

fun IdeaModuleInfo.findJvmStdlibAcrossDependencies(): LibraryInfo? {
    val project = project ?: return null

    return KotlinStdlibCache.getInstance(project).findStdlibInModuleDependencies(this)
}

fun getModuleInfoByVirtualFile(project: Project, virtualFile: VirtualFile): IdeaModuleInfo? =
    collectInfosByVirtualFile(
        project, virtualFile,
        treatAsLibrarySource = false,
        onOccurrence = { return@getModuleInfoByVirtualFile it }
    )

fun getBinaryLibrariesModuleInfos(project: Project, virtualFile: VirtualFile) =
    collectModuleInfosByType<BinaryModuleInfo>(
        project,
        virtualFile
    )

fun getLibrarySourcesModuleInfos(project: Project, virtualFile: VirtualFile) =
    collectModuleInfosByType<LibrarySourceInfo>(
        project,
        virtualFile
    )

fun getScriptRelatedModuleInfo(project: Project, virtualFile: VirtualFile): ModuleSourceInfo? {
    val moduleRelatedModuleInfo = getModuleRelatedModuleInfo(project, virtualFile)
    if (moduleRelatedModuleInfo != null) {
        return moduleRelatedModuleInfo
    }

    return if (ScratchFileService.getInstance().getRootType(virtualFile) is ScratchRootType) {
        val scratchModule = ScriptRelatedModuleNameFile[project, virtualFile]?.let { ModuleManager.getInstance(project).findModuleByName(it) }
        scratchModule?.testSourceInfo() ?: scratchModule?.productionSourceInfo()
    } else null
}

private typealias VirtualFileProcessor<T> = (Project, VirtualFile, Boolean) -> T

private sealed class ModuleInfoCollector<out T>(
    val onResult: (IdeaModuleInfo?) -> T,
    val onFailure: (String) -> T,
    val virtualFileProcessor: VirtualFileProcessor<T>
) {
    object NotNullTakeFirst : ModuleInfoCollector<IdeaModuleInfo>(
        onResult = { it ?: NotUnderContentRootModuleInfo },
        onFailure = { reason ->
            LOG.error("Could not find correct module information.\nReason: $reason")
            NotUnderContentRootModuleInfo
        },
        virtualFileProcessor = processor@{ project, virtualFile, isLibrarySource ->
            collectInfosByVirtualFile(
                project,
                virtualFile,
                isLibrarySource
            ) {
                return@processor it ?: NotUnderContentRootModuleInfo
            }
        }
    )

    object NullableTakeFirst : ModuleInfoCollector<IdeaModuleInfo?>(
        onResult = { it },
        onFailure = { reason ->
            LOG.warn("Could not find correct module information.\nReason: $reason")
            null
        },
        virtualFileProcessor = processor@{ project, virtualFile, isLibrarySource ->
            collectInfosByVirtualFile(
                project,
                virtualFile,
                isLibrarySource
            ) { return@processor it }
        }
    )

    object ToSequence : ModuleInfoCollector<Sequence<IdeaModuleInfo>>(
        onResult = { result -> result?.let { sequenceOf(it) } ?: emptySequence() },
        onFailure = { reason ->
            LOG.warn("Could not find correct module information.\nReason: $reason")
            emptySequence()
        },
        virtualFileProcessor = { project, virtualFile, isLibrarySource ->
            sequence {
                collectInfosByVirtualFile(
                    project,
                    virtualFile,
                    isLibrarySource
                ) { yieldIfNotNull(it) }
            }
        }
    )
}

private fun <T> PsiElement.collectInfos(
    c: ModuleInfoCollector<T>,
    createSourceLibraryInfoForLibraryBinaries: Boolean = true
): T {
    (containingFile?.forcedModuleInfo as? IdeaModuleInfo)?.let {
        return c.onResult(it)
    }

    if (this is KtLightElement<*, *>) {
        return this.processLightElement(c)
    }

    collectModuleInfoByUserData(this).firstOrNull()?.let {
        return c.onResult(it)
    }

    val containingFile =
        containingFile ?: return c.onFailure("Analyzing element of type ${this::class.java} with no containing file\nText:\n$text")

    val containingKtFile = containingFile as? KtFile
    containingKtFile?.analysisContext?.let {
        return it.collectInfos(c)
    }

    containingKtFile?.doNotAnalyze?.let {
        return c.onFailure("Should not analyze element: $text in file ${containingKtFile.name}\n$it")
    }

    val explicitModuleInfo = containingKtFile?.forcedModuleInfo ?: (containingKtFile?.originalFile as? KtFile)?.forcedModuleInfo
    if (explicitModuleInfo is IdeaModuleInfo) {
        return c.onResult(explicitModuleInfo)
    }

    if (containingKtFile is KtCodeFragment) {
        val context = containingKtFile.getContext()
            ?: return c.onFailure("Analyzing code fragment of type ${containingKtFile::class.java} with no context element\nText:\n${containingKtFile.getText()}")
        return context.collectInfos(c)
    }

    val virtualFile = containingFile.originalFile.virtualFile
        ?: return c.onFailure("Analyzing element of type ${this::class.java} in non-physical file $containingFile of type ${containingFile::class.java}\nText:\n$text")

    val isScript = runReadAction { containingKtFile?.isScript() == true }
    if (isScript) {
        getModuleRelatedModuleInfo(project, virtualFile)?.let {
            return c.onResult(it)
        }
        val script = runReadAction { containingKtFile?.script }
        script?.let {
            containingKtFile?.findScriptDefinition()?.let {
                return c.onResult(ScriptModuleInfo(project, virtualFile, it))
            }
        }
    }
    val isCompiled = (containingFile as? KtFile)?.isCompiled

    val isLibrarySource = if (createSourceLibraryInfoForLibraryBinaries) {
        isCompiled ?: false
    } else {
        isCompiled == false
    }

    return c.virtualFileProcessor(
        project,
        virtualFile,
        isLibrarySource
    )
}

private fun <T> KtLightElement<*, *>.processLightElement(c: ModuleInfoCollector<T>): T {
    val decompiledClass = this.getParentOfType<KtLightClassForDecompiledDeclaration>(strict = false)
    if (decompiledClass != null) {
        return c.virtualFileProcessor(
            project,
            containingFile.virtualFile.sure { "Decompiled class should be build from physical file" },
            false
        )
    }

    val element = kotlinOrigin ?: when (this) {
        is KtLightClassForFacade -> this.files.first()
        else -> return c.onFailure("Light element without origin is referenced by resolve:\n$this\n${this.clsDelegate.text}")
    }

    return element.collectInfos(c)
}

private inline fun <T> collectInfosByVirtualFile(
    project: Project,
    virtualFile: VirtualFile,
    treatAsLibrarySource: Boolean,
    onOccurrence: (IdeaModuleInfo?) -> T
): T {
    collectModuleInfoByUserData(project, virtualFile).map(onOccurrence)

    val moduleRelatedModuleInfo = getModuleRelatedModuleInfo(project, virtualFile)
    if (moduleRelatedModuleInfo != null) {
        onOccurrence(moduleRelatedModuleInfo)
    }

    val projectFileIndex = ProjectFileIndex.getInstance(project)
    projectFileIndex.getOrderEntriesForFile(virtualFile).forEach {
        it.toIdeaModuleInfo(project, virtualFile, treatAsLibrarySource).map(onOccurrence)
    }

    val isBinary = virtualFile.fileType.isKotlinBinary()
    val scriptConfigurationManager = ScriptConfigurationManager.getInstance(project)
    if (isBinary && virtualFile in scriptConfigurationManager.getAllScriptsDependenciesClassFilesScope()) {
        if (treatAsLibrarySource) {
            onOccurrence(ScriptDependenciesSourceInfo.ForProject(project))
        } else {
            onOccurrence(ScriptDependenciesInfo.ForProject(project))
        }
    }
    if (!isBinary && virtualFile in scriptConfigurationManager.getAllScriptDependenciesSourcesScope()) {
        onOccurrence(ScriptDependenciesSourceInfo.ForProject(project))
    }

    return onOccurrence(null)
}

private fun getModuleRelatedModuleInfo(project: Project, virtualFile: VirtualFile): ModuleSourceInfo? {
    val projectFileIndex = ProjectFileIndex.getInstance(project)

    val module = projectFileIndex.getModuleForFile(virtualFile)
    if (module != null && !module.isDisposed) {
        val moduleFileIndex = ModuleRootManager.getInstance(module).fileIndex
        if (moduleFileIndex.isInTestSourceContentKotlinAware(virtualFile)) {
            return module.testSourceInfo()
        } else if (moduleFileIndex.isInSourceContentWithoutInjected(virtualFile)) {
            return module.productionSourceInfo()
        }
    }

    val fileOrigin = OutsidersPsiFileSupportUtils.getOutsiderFileOrigin(project, virtualFile)
    if (fileOrigin != null) {
        return getModuleRelatedModuleInfo(project, fileOrigin)
    }

    return null
}

private inline fun <reified T : IdeaModuleInfo> collectModuleInfosByType(project: Project, virtualFile: VirtualFile): Collection<T> {
    val result = linkedSetOf<T>()
    collectInfosByVirtualFile(project, virtualFile, treatAsLibrarySource = false) {
        result.addIfNotNull(it as? T)
    }

    return result
}

private fun OrderEntry.toIdeaModuleInfo(
    project: Project,
    virtualFile: VirtualFile,
    treatAsLibrarySource: Boolean = false
): List<IdeaModuleInfo> {
    if (this is ModuleOrderEntry) return emptyList()
    if (!isValid) return emptyList()

    when (this) {
        is LibraryOrderEntry -> {
            val library = library ?: return emptyList()
            if (!treatAsLibrarySource && ProjectRootsUtil.isLibraryClassFile(project, virtualFile)) {
                return createLibraryInfo(project, library)
            } else if (treatAsLibrarySource || ProjectRootsUtil.isLibraryFile(project, virtualFile)) {
                return createLibraryInfo(project, library).map { it.sourcesModuleInfo }
            }
        }
        is JdkOrderEntry -> {
            return listOf(SdkInfo(project, jdk ?: return emptyList()))
        }
        else -> return emptyList()
    }
    return emptyList()
}

/**
 * @see [org.jetbrains.kotlin.types.typeUtil.closure].
 */
fun <T> Collection<T>.lazyClosure(f: (T) -> Collection<T>): Sequence<T> = sequence {
    if (isEmpty()) return@sequence
    var sizeBeforeIteration = 0

    yieldAll(this@lazyClosure)
    var yieldedCount = size
    var elementsToCheck = this@lazyClosure

    while (yieldedCount > sizeBeforeIteration) {
        val toAdd = hashSetOf<T>()
        elementsToCheck.forEach {
            val neighbours = f(it)
            yieldAll(neighbours)
            yieldedCount += neighbours.size
            toAdd.addAll(neighbours)
        }
        elementsToCheck = toAdd
        sizeBeforeIteration = yieldedCount
    }
}
