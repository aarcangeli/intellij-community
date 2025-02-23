// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.storage.bridgeEntities.api

import com.intellij.workspaceModel.storage.WorkspaceEntity
import com.intellij.workspaceModel.storage.url.VirtualFileUrl
import com.intellij.workspaceModel.storage.EntitySource
import com.intellij.workspaceModel.storage.ModifiableWorkspaceEntity
import com.intellij.workspaceModel.storage.referrersx
import org.jetbrains.deft.ObjBuilder
import org.jetbrains.deft.Type
import org.jetbrains.deft.annotations.Child
import com.intellij.workspaceModel.storage.GeneratedCodeApiVersion
import com.intellij.workspaceModel.storage.MutableEntityStorage




/**
 * Stores data from [EclipseModuleManagerImpl] in workspace model
 */
interface EclipseProjectPropertiesEntity : WorkspaceEntity {
  val module: ModuleEntity

  val variablePaths: Map<String, String>

  // This should be a set
  val eclipseUrls: List<VirtualFileUrl>

  // This should be a set
  val unknownCons: List<String>

  // This should be a set
  val knownCons: List<String>
  val forceConfigureJdk: Boolean
  val expectedModuleSourcePlace: Int
  val srcPlace: Map<String, Int>
  //region generated code
  //@formatter:off
  @GeneratedCodeApiVersion(0)
  interface Builder: EclipseProjectPropertiesEntity, ModifiableWorkspaceEntity<EclipseProjectPropertiesEntity>, ObjBuilder<EclipseProjectPropertiesEntity> {
      override var module: ModuleEntity
      override var entitySource: EntitySource
      override var variablePaths: Map<String, String>
      override var eclipseUrls: List<VirtualFileUrl>
      override var unknownCons: List<String>
      override var knownCons: List<String>
      override var forceConfigureJdk: Boolean
      override var expectedModuleSourcePlace: Int
      override var srcPlace: Map<String, Int>
  }
  
  companion object: Type<EclipseProjectPropertiesEntity, Builder>() {
      operator fun invoke(entitySource: EntitySource, variablePaths: Map<String, String>, eclipseUrls: List<VirtualFileUrl>, unknownCons: List<String>, knownCons: List<String>, forceConfigureJdk: Boolean, expectedModuleSourcePlace: Int, srcPlace: Map<String, Int>, init: (Builder.() -> Unit)? = null): EclipseProjectPropertiesEntity {
          val builder = builder()
          builder.entitySource = entitySource
          builder.variablePaths = variablePaths
          builder.eclipseUrls = eclipseUrls
          builder.unknownCons = unknownCons
          builder.knownCons = knownCons
          builder.forceConfigureJdk = forceConfigureJdk
          builder.expectedModuleSourcePlace = expectedModuleSourcePlace
          builder.srcPlace = srcPlace
          init?.invoke(builder)
          return builder
      }
  }
  //@formatter:on
  //endregion

}
//region generated code
fun MutableEntityStorage.modifyEntity(entity: EclipseProjectPropertiesEntity, modification: EclipseProjectPropertiesEntity.Builder.() -> Unit) = modifyEntity(EclipseProjectPropertiesEntity.Builder::class.java, entity, modification)
//endregion

val ModuleEntity.eclipseProperties: @Child EclipseProjectPropertiesEntity?
  get() = referrersx(EclipseProjectPropertiesEntity::module).singleOrNull()
