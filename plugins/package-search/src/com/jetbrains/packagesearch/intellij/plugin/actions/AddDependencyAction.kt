package com.jetbrains.packagesearch.intellij.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilBase
import com.jetbrains.packagesearch.PackageSearchIcons
import com.jetbrains.packagesearch.intellij.plugin.PackageSearchBundle
import com.jetbrains.packagesearch.intellij.plugin.extensibility.ProjectModuleOperationProvider
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.PackageSearchToolWindowFactory
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.models.ModuleModel
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.models.TargetModules
import com.jetbrains.packagesearch.intellij.plugin.util.packageSearchProjectService
import com.jetbrains.packagesearch.intellij.plugin.util.uiStateModifier

class AddDependencyAction : AnAction(
    PackageSearchBundle.message("packagesearch.actions.addDependency.text"),
    PackageSearchBundle.message("packagesearch.actions.addDependency.description"),
    PackageSearchIcons.Artifact
) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)

        e.presentation.isEnabledAndVisible = project != null
            && editor != null
            && run {
            val psiFile: PsiFile? = PsiUtilBase.getPsiFileInEditor(editor, project)
            if (psiFile == null || ProjectModuleOperationProvider.forProjectPsiFileOrNull(project, psiFile) == null) {
                return@run false
            }

            val modules = project.packageSearchProjectService.moduleModelsStateFlow.value
            findSelectedModule(e, modules) != null
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val modules = project.packageSearchProjectService.moduleModelsStateFlow.value
        if (modules.isEmpty()) return

        val selectedModule = findSelectedModule(e, modules) ?: return

        PackageSearchToolWindowFactory.activateToolWindow(project) {
            project.uiStateModifier.setTargetModules(TargetModules.One(selectedModule))
        }
    }

    private fun findSelectedModule(e: AnActionEvent, modules: List<ModuleModel>): ModuleModel? {
        val project = e.project ?: return null
        val file = obtainSelectedProjectDirIfSingle(e)?.virtualFile ?: return null
        val selectedModule = runReadAction { ModuleUtilCore.findModuleForFile(file, project) } ?: return null

        // Sanity check that the module we got actually exists
        ModuleManager.getInstance(project).findModuleByName(selectedModule.name)
            ?: return null

        return modules.firstOrNull { module -> module.projectModule.nativeModule == selectedModule }
    }

    private fun obtainSelectedProjectDirIfSingle(e: AnActionEvent): PsiDirectory? {
        val dataContext = e.dataContext
        val ideView = LangDataKeys.IDE_VIEW.getData(dataContext)
        val selectedDirectories = ideView?.directories ?: return null

        if (selectedDirectories.size != 1) return null

        return selectedDirectories.first()
    }
}
