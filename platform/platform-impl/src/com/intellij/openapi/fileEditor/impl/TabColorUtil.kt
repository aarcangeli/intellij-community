// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

fun getForegroundColorForFile(project: Project, file: VirtualFile): ColorKey? {
  val firstOrNull = EditorTabColorProvider.EP_NAME.extensionList.firstOrNull {
    val editorTabColor = it.getEditorTabForegroundColor(project, file)
    return@firstOrNull editorTabColor != null
  }
  return firstOrNull?.getEditorTabForegroundColor(project, file)
}

