// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.fe10.plugin

import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginKind
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginKindProvider

class Fe10KotlinPluginKindProvider : KotlinPluginKindProvider() {
    override fun getPluginKind(): KotlinPluginKind = KotlinPluginKind.FE10_PLUGIN
}