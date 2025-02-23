// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui

import com.fasterxml.jackson.jr.ob.JSON
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.RegistryBooleanOptionDescriptor
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.laf.LafManagerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.registry.Registry
import java.io.IOException
import java.util.*
import javax.swing.UIManager.LookAndFeelInfo

/**
 * @author Konstantin Bulenkov
 */
class ExperimentalUIImpl : ExperimentalUI() {
  override fun loadIconMappings() = loadIconMappingsImpl()

  override fun onExpUIEnabled() {
    setRegistryKeyIfNecessary("ide.experimental.ui", true)
    setRegistryKeyIfNecessary("debugger.new.tool.window.layout", true)
    UISettings.getInstance().openInPreviewTabIfPossible = true
    val name = if (JBColor.isBright()) "Light" else "Dark"
    val laf = LafManagerImpl.getInstance().installedLookAndFeels.first { x: LookAndFeelInfo -> x.name == name }
    if (laf != null) {
      LafManagerImpl.getInstance().currentLookAndFeel = laf
    }
    ApplicationManager.getApplication().invokeLater({ RegistryBooleanOptionDescriptor.suggestRestart(null) }, ModalityState.NON_MODAL)
  }

  override fun onExpUIDisabled() {
    setRegistryKeyIfNecessary("ide.experimental.ui", false)
    setRegistryKeyIfNecessary("debugger.new.tool.window.layout", false)
    val mgr = LafManager.getInstance() as LafManagerImpl
    val currentLafName = mgr.currentLookAndFeel?.name
    if (currentLafName == "Dark" || currentLafName == "Light") {
      mgr.setCurrentLookAndFeel(if (JBColor.isBright()) mgr.defaultLightLaf else mgr.defaultDarkLaf)
    }
    ApplicationManager.getApplication().invokeLater({ RegistryBooleanOptionDescriptor.suggestRestart(null) }, ModalityState.NON_MODAL)
  }

  fun setRegistryKeyIfNecessary(key: String, value: Boolean) {
    if (Registry.`is`(key) != value) {
      Registry.get(key).setValue(value)
    }
  }

  companion object {
    @JvmStatic
    fun loadIconMappingsImpl(): Map<String, String> {
      val json = JSON.builder().enable().enable(JSON.Feature.READ_ONLY).build()
      try {
        val fin = Objects.requireNonNull(ExperimentalUIImpl::class.java.getResource("ExpUIIconMapping.json")).openStream()
        return mutableMapOf<String, String>().apply { readDataFromJson(json.mapFrom(fin), "", this) }
      }
      catch (ignore: IOException) {
      }
      return emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    private fun readDataFromJson(json: Map<String, Any>, prefix: String, result: MutableMap<String, String>) {
      json.forEach { (key, value) ->
        when (value) {
          is String -> result[value] = prefix + key
          is Map<*, *> -> readDataFromJson(value as Map<String, Any>, "$prefix$key/", result)
          is List<*> -> value.forEach { result[it as String] = "$prefix$key" }
        }
      }
    }
  }
}