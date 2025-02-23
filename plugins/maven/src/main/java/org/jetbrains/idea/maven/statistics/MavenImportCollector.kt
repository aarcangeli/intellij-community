// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.statistics

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector

class MavenImportCollector : CounterUsagesCollector() {
  companion object {
    val GROUP = EventLogGroup("maven.import", 2)

    @JvmField
    val HAS_USER_ADDED_LIBRARY_DEP = GROUP.registerEvent("hasUserAddedLibraryDependency")

    @JvmField
    val HAS_USER_ADDED_MODULE_DEP = GROUP.registerEvent("hasUserAddedModuleDependency")

    @JvmField
    val HAS_USER_MODIFIED_IMPORTED_LIBRARY = GROUP.registerEvent("hasUserModifiedImportedLibrary")

    @JvmField
    val IMPORTER_CLASS = EventFields.Class("importer_class")

    @JvmField
    val NUMBER_OF_MODULES = EventFields.RoundedInt("number_of_modules")

    @JvmField
    val TOTAL_DURATION_MS = EventFields.Long("total_duration_ms")

    @JvmField
    val IMPORTER_RUN = GROUP.registerEvent("importer_run", IMPORTER_CLASS, NUMBER_OF_MODULES, TOTAL_DURATION_MS)
  }

  override fun getGroup(): EventLogGroup {
    return GROUP
  }
}