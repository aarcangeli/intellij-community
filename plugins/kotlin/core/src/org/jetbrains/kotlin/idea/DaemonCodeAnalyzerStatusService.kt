// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project

class DaemonCodeAnalyzerStatusService(project: Project) : Disposable {
    companion object {
        fun getInstance(project: Project): DaemonCodeAnalyzerStatusService = project.service()
    }

    @Volatile
    var daemonRunning: Boolean = false
        private set

    init {
        val messageBusConnection = project.messageBus.connect(this)
        messageBusConnection.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonStarting(fileEditors: MutableCollection<out FileEditor>) {
                daemonRunning = true
            }

            override fun daemonFinished(fileEditors: MutableCollection<out FileEditor>) {
                daemonRunning = false
            }

            override fun daemonCancelEventOccurred(reason: String) {
                daemonRunning = false
            }
        })
    }

    override fun dispose() {

    }
}