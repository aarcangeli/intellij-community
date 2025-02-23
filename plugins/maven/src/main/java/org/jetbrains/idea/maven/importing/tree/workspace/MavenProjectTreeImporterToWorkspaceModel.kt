// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.importing.tree.workspace

import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ExternalProjectSystemRegistry
import com.intellij.workspaceModel.ide.JpsImportedEntitySource
import com.intellij.workspaceModel.ide.WorkspaceModel
import com.intellij.workspaceModel.ide.impl.legacyBridge.module.ModuleManagerBridgeImpl.Companion.findModuleByEntity
import com.intellij.workspaceModel.storage.MutableEntityStorage
import com.intellij.workspaceModel.storage.bridgeEntities.api.ModuleId
import org.jetbrains.idea.maven.importing.tree.MavenModuleImportContext
import org.jetbrains.idea.maven.importing.tree.MavenModuleImportData
import org.jetbrains.idea.maven.importing.tree.MavenProjectImportContextProvider
import org.jetbrains.idea.maven.importing.workspaceModel.MavenProjectImporterWorkspaceBase
import org.jetbrains.idea.maven.project.*
import org.jetbrains.idea.maven.utils.MavenUtil
import java.util.*

class MavenProjectTreeImporterToWorkspaceModel(
  projectsTree: MavenProjectsTree,
  projectsToImportWithChanges: Map<MavenProject, MavenProjectChanges>,
  importingSettings: MavenImportingSettings,
  modelsProvider: IdeModifiableModelsProvider,
  project: Project
) : MavenProjectImporterWorkspaceBase(projectsTree, projectsToImportWithChanges, importingSettings, modelsProvider, project) {

  private val createdModulesList = ArrayList<Module>()
  private val contextProvider = MavenProjectImportContextProvider(project, projectsTree,
                                                                  projectsToImportWithChanges, myImportingSettings)

  override fun importProject(): List<MavenProjectsProcessorTask> {
    val postTasks = ArrayList<MavenProjectsProcessorTask>()
    val context = contextProvider.context
    if (context.hasChanges) {
      try {
        importModules(context, postTasks)
      }
      finally {
        MavenUtil.invokeAndWaitWriteAction(myProject) { myModelsProvider.dispose() }
      }
      scheduleRefreshResolvedArtifacts(postTasks)
    }
    return postTasks
  }

  private fun importModules(context: MavenModuleImportContext, postTasks: List<MavenProjectsProcessorTask>) {
    val builder = MutableEntityStorage.create()

    val createdModuleIds = ArrayList<Pair<MavenModuleImportData, ModuleId>>()
    val mavenFolderHolderByMavenId = TreeMap<String, MavenImportFolderHolder>()

    for (importData in context.allModules) {
      val moduleEntity = WorkspaceModuleImporter(
        importData, virtualFileUrlManager, builder, myImportingSettings, mavenFolderHolderByMavenId, myProject
      ).importModule()
      createdModuleIds.add(importData to moduleEntity.persistentId)
    }

    val moduleImportData = mutableListOf<ModuleImportData>()
    MavenUtil.invokeAndWaitWriteAction(myProject) {
      WorkspaceModel.getInstance(myProject).updateProjectModel { current ->
        current.replaceBySource(
          { (it as? JpsImportedEntitySource)?.externalSystemId == ExternalProjectSystemRegistry.MAVEN_EXTERNAL_SOURCE_ID }, builder)
      }
      val storage = WorkspaceModel.getInstance(myProject).entityStorage.current
      for ((importData, moduleId) in createdModuleIds) {
        val entity = storage.resolve(moduleId)
        if (entity == null) continue
        val module = storage.findModuleByEntity(entity)
        if (module != null) {
          createdModulesList.add(module)
          moduleImportData.add(ModuleImportData(module, importData.mavenProject, importData.moduleData.type))
        }
      }
    }

    finalizeImport(moduleImportData, context.moduleNameByProject, postTasks)

  }

  override fun createdModules(): List<Module> {
    return createdModulesList
  }
}