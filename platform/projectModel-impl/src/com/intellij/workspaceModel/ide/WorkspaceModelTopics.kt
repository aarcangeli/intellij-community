// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.ide

import com.intellij.diagnostic.ActivityCategory
import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.messages.Topic
import com.intellij.workspaceModel.storage.VersionedStorageChange
import java.util.*

interface WorkspaceModelChangeListener : EventListener {
  @JvmDefault
  fun beforeChanged(event: VersionedStorageChange) {}
  @JvmDefault
  fun changed(event: VersionedStorageChange) {}
}

/**
 * Topics to subscribe to Workspace changes
 *
 * Please use [subscribeImmediately] and [subscribeAfterModuleLoading] to subscribe to changes
 */
class WorkspaceModelTopics : Disposable {
  companion object {
    /** Please use [subscribeImmediately] and [subscribeAfterModuleLoading] to subscribe to changes */
    @Topic.ProjectLevel
    private val CHANGED = Topic(WorkspaceModelChangeListener::class.java, Topic.BroadcastDirection.NONE, true)

    @JvmStatic
    fun getInstance(project: Project): WorkspaceModelTopics = project.service()
  }

  private val allEvents = ContainerUtil.createConcurrentList<EventsDispatcher>()
  private var sendToQueue = true
  var modulesAreLoaded = false

  /**
   * Subscribe to topic and start to receive changes immediately.
   *
   * Topic is project-level only without broadcasting - connection expected to be to project message bus only.
   */
  fun subscribeImmediately(connection: MessageBusConnection, listener: WorkspaceModelChangeListener) {
    connection.subscribe(CHANGED, listener)
  }

  /**
   * Subscribe to the topic and start to receive changes only *after* all the modules get loaded.
   * All the events that will be fired before the modules loading, will be collected to the queue. After the modules are loaded, all events
   *   from the queue will be dispatched to listener under the write action and the further events will be dispatched to listener
   *   without passing to event queue.
   *
   * Topic is project-level only without broadcasting - connection expected to be to project message bus only.
   */
  fun subscribeAfterModuleLoading(connection: MessageBusConnection, listener: WorkspaceModelChangeListener) {
    if (!sendToQueue) {
      subscribeImmediately(connection, listener)
    }
    else {
      val queue = EventsDispatcher(listener)
      allEvents += queue
      subscribeImmediately(connection, queue)
    }
  }

  fun syncPublisher(messageBus: MessageBus): WorkspaceModelChangeListener = messageBus.syncPublisher(CHANGED)

  fun notifyModulesAreLoaded() {
    val activity = StartUpMeasurer.startActivity("postponed events sending", ActivityCategory.DEFAULT)
    sendToQueue = false
    if (allEvents.isNotEmpty() && allEvents.any { it.events.isNotEmpty() }) {
      val activityInQueue = activity.startChild("events sending (in queue)")
      val application = ApplicationManager.getApplication()
      application.invokeAndWait {
        application.runWriteAction {
          val innerActivity = activityInQueue.endAndStart("events sending")
          allEvents.forEach { queue ->
            queue.collectToQueue = false
            queue.events.forEach { (isBefore, event) ->
              if (isBefore) queue.originalListener.beforeChanged(event)
              else queue.originalListener.changed(event)
            }
            queue.events.clear()
          }
          innerActivity.end()
        }
      }
    }
    else {
      allEvents.forEach { queue -> queue.collectToQueue = false }
    }
    allEvents.clear()
    modulesAreLoaded = true
    activity.end()
  }

  private class EventsDispatcher(val originalListener: WorkspaceModelChangeListener) : WorkspaceModelChangeListener {
    val events = mutableListOf<Pair<Boolean, VersionedStorageChange>>()
    var collectToQueue = true

    override fun beforeChanged(event: VersionedStorageChange) {
      if (collectToQueue) {
        events += true to event
      }
      else {
        originalListener.beforeChanged(event)
      }
    }

    override fun changed(event: VersionedStorageChange) {
      if (collectToQueue) {
        events += false to event
      }
      else {
        originalListener.changed(event)
      }
    }

  }

  override fun dispose() {
    allEvents.forEach { it.events.clear() }
    allEvents.clear()
  }
}
