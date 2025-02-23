// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.storage.entities.test.api

import com.intellij.workspaceModel.storage.EntitySource
import com.intellij.workspaceModel.storage.GeneratedCodeApiVersion
import com.intellij.workspaceModel.storage.ModifiableWorkspaceEntity
import com.intellij.workspaceModel.storage.WorkspaceEntity
import org.jetbrains.deft.ObjBuilder
import org.jetbrains.deft.Type
import com.intellij.workspaceModel.storage.MutableEntityStorage


interface DefaultValueEntity: WorkspaceEntity {
  val name: String
  val isGenerated: Boolean
    get() = true
  val anotherName: String
    get() = "Another Text"
  //region generated code
  //@formatter:off
  @GeneratedCodeApiVersion(0)
  interface Builder: DefaultValueEntity, ModifiableWorkspaceEntity<DefaultValueEntity>, ObjBuilder<DefaultValueEntity> {
      override var name: String
      override var entitySource: EntitySource
      override var isGenerated: Boolean
      override var anotherName: String
  }
  
  companion object: Type<DefaultValueEntity, Builder>() {
      operator fun invoke(name: String, entitySource: EntitySource, init: (Builder.() -> Unit)? = null): DefaultValueEntity {
          val builder = builder()
          builder.name = name
          builder.entitySource = entitySource
          init?.invoke(builder)
          return builder
      }
  }
  //@formatter:on
  //endregion

}
//region generated code
fun MutableEntityStorage.modifyEntity(entity: DefaultValueEntity, modification: DefaultValueEntity.Builder.() -> Unit) = modifyEntity(DefaultValueEntity.Builder::class.java, entity, modification)
//endregion