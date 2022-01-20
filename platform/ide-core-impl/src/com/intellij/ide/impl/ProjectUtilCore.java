// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.impl;

import com.intellij.ide.highlighter.ProjectFileType;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.Strings;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.io.PathKt;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class ProjectUtilCore {
  public static @NotNull Project @NotNull [] getOpenProjects() {
    ProjectManager projectManager = ProjectManager.getInstanceIfCreated();
    return projectManager == null ? new Project[0] : projectManager.getOpenProjects();
  }

  public static boolean isValidProjectPath(@NotNull Path file) {
    file = getRomoloProjectPath(file);
    return Files.isDirectory(file.resolve(Project.DIRECTORY_STORE_FOLDER)) ||
           (Strings.endsWith(file.toString(), ProjectFileType.DOT_DEFAULT_EXTENSION) && Files.isRegularFile(file));
  }

  /**
   * ROMOLO EDIT: idea configuration is stored outside the project directory
   */
  public static Path getRomoloProjectPath(Path file) {
    String filename = PathKt.sanitizeFileName(file.toFile().getName(), "_", true, null);
    String hash = Integer.toHexString(file.toFile().getAbsolutePath().toLowerCase(Locale.ROOT).hashCode());
    return Path.of(PathManager.getConfigPath(), "projects", filename + "." + hash);
  }

  @ApiStatus.Internal
  public static @Nullable VirtualFile getFileAndRefresh(@NotNull Path file) {
    VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(FileUtil.toSystemIndependentName(file.toString()));
    if (virtualFile == null || !virtualFile.isValid()) {
      return null;
    }

    virtualFile.refresh(false, false);
    return virtualFile;
  }
}
