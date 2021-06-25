package com.intellij.json;

import com.intellij.lang.Language;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JsonLanguage extends Language {
  public static final JsonLanguage INSTANCE = new JsonLanguage();

  protected JsonLanguage(String ID, String... mimeTypes) {
    super(INSTANCE, ID, mimeTypes);
  }

  private JsonLanguage() {
    super("JSON", "application/json", "application/vnd.api+json", "application/hal+json", "application/ld+json");
  }

  @Override
  public boolean isCaseSensitive() {
    return true;
  }

  public boolean hasPermissiveStrings() { return false; }

  // ROMOLO EDIT: use custom schema for json files
  @Nullable public VirtualFile getSchemaFile(@NotNull Project project, @NotNull VirtualFile file) {
    return null;
  }
}
