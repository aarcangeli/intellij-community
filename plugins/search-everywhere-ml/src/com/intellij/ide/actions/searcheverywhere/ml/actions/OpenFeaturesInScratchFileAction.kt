package com.intellij.ide.actions.searcheverywhere.ml.actions

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereUI
import com.intellij.ide.actions.searcheverywhere.ml.SearchEverywhereMlSessionService
import com.intellij.ide.actions.searcheverywhere.ml.SearchEverywhereTabWithMl
import com.intellij.ide.scratch.ScratchFileCreationHelper
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.json.JsonFileType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile

class OpenFeaturesInScratchFileAction : AnAction() {
  companion object {
    private const val SHOULD_ORDER_BY_ML_KEY = "shouldOrderByMl"
    private const val CONTEXT_INFO_KEY = "contextInfo"
    private const val SEARCH_STATE_FEATURES_KEY = "searchStateFeatures"
    private const val CONTRIBUTORS_KEY = "contributors"
    private const val FOUND_ELEMENTS_KEY = "foundElements"
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = shouldActionBeEnabled(e)
  }

  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.EDT
  }

  private fun shouldActionBeEnabled(e: AnActionEvent): Boolean {
    val seManager = SearchEverywhereManager.getInstance(e.project)
    val session = SearchEverywhereMlSessionService.getService().getCurrentSession()

    return e.project != null
           && seManager.isShown
           && session != null
           && session.getCurrentSearchState() != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val searchEverywhereUI = SearchEverywhereManager.getInstance(e.project).currentlyShownUI

    val report = getFeaturesReport(searchEverywhereUI)
    val json = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report)

    val context = createScratchFileContext(json)
    val scratchFile = createNewJsonScratchFile(e.project!!, context)
    openScratchFile(scratchFile, e.project!!)
  }

  private fun getFeaturesReport(searchEverywhereUI: SearchEverywhereUI): Map<String, Any> {
    val mlSessionService = SearchEverywhereMlSessionService.getService()
    val searchSession = mlSessionService.getCurrentSession()!!
    val state = searchSession.getCurrentSearchState()

    val tabId = searchEverywhereUI.selectedTabID
    val features = searchEverywhereUI.foundElementsInfo.map { info ->
      val rankingWeight = info.priority
      val contributor = info.contributor.searchProviderId
      val elementName = StringUtil.notNullize(info.element.toString(), "undefined")
      val mlWeight = if (isTabWithMl(tabId)) mlSessionService.getMlWeight(info.contributor, info.element, rankingWeight) else null

      val elementId = searchSession.itemIdProvider.getId(info.element)
      return@map ElementFeatures(
        elementId,
        elementName,
        mlWeight,
        rankingWeight,
        contributor,
        state!!.getElementFeatures(elementId, info.element, info.contributor, rankingWeight).featuresAsMap().toSortedMap()
      )
    }

    val contributors = searchEverywhereUI.foundElementsInfo.map { info -> info.contributor }.toHashSet()
    return mapOf(
      SHOULD_ORDER_BY_ML_KEY to mlSessionService.shouldOrderByMl(),
      CONTEXT_INFO_KEY to searchSession.cachedContextInfo.features.associate { it.field.name to it.data },
      SEARCH_STATE_FEATURES_KEY to state!!.searchStateFeatures.associate { it.field.name to it.data },
      CONTRIBUTORS_KEY to contributors.map { c -> ContributorInfo(c.searchProviderId, c.sortWeight) },
      FOUND_ELEMENTS_KEY to features
    )
  }

  private fun isTabWithMl(tabId: String): Boolean {
    return SearchEverywhereTabWithMl.findById(tabId) != null
  }

  private fun createScratchFileContext(json: String) = ScratchFileCreationHelper.Context().apply {
    text = json
    fileExtension = JsonFileType.DEFAULT_EXTENSION
    createOption = ScratchFileService.Option.create_if_missing
  }

  private fun createNewJsonScratchFile(project: Project, context: ScratchFileCreationHelper.Context): VirtualFile {
    val fileName = "search-everywhere-features.${context.fileExtension}"
    return ScratchRootType.getInstance().createScratchFile(project, fileName, context.language, context.text, context.createOption)!!
  }

  private fun openScratchFile(file: VirtualFile, project: Project) {
    FileEditorManager.getInstance(project).openFile(file, true)
  }

  @JsonPropertyOrder("id", "name", "mlWeight", "rankingWeight", "contributor", "features")
  private data class ElementFeatures(val id: Int?,
                                     val name: String,
                                     val mlWeight: Double?,
                                     val rankingWeight: Int,
                                     val contributor: String,
                                     val features: Map<String, Any>)

  @JsonPropertyOrder("id", "weight")
  private data class ContributorInfo(val id: String, val weight: Int)
}