package com.intellij.workspaceModel.storage.entities.test.api

import com.intellij.workspaceModel.storage.EntitySource
import com.intellij.workspaceModel.storage.GeneratedCodeApiVersion
import com.intellij.workspaceModel.storage.ModifiableWorkspaceEntity
import com.intellij.workspaceModel.storage.WorkspaceEntity
import org.jetbrains.deft.ObjBuilder
import org.jetbrains.deft.Type
import org.jetbrains.deft.annotations.Abstract
import org.jetbrains.deft.annotations.Child
import com.intellij.workspaceModel.storage.MutableEntityStorage



interface ParentChainEntity : WorkspaceEntity {
  val root: @Child CompositeAbstractEntity


  //region generated code
  //@formatter:off
  @GeneratedCodeApiVersion(0)
  interface Builder: ParentChainEntity, ModifiableWorkspaceEntity<ParentChainEntity>, ObjBuilder<ParentChainEntity> {
      override var root: CompositeAbstractEntity
      override var entitySource: EntitySource
  }
  
  companion object: Type<ParentChainEntity, Builder>() {
      operator fun invoke(entitySource: EntitySource, init: (Builder.() -> Unit)? = null): ParentChainEntity {
          val builder = builder()
          builder.entitySource = entitySource
          init?.invoke(builder)
          return builder
      }
  }
  //@formatter:on
  //endregion

}
//region generated code
fun MutableEntityStorage.modifyEntity(entity: ParentChainEntity, modification: ParentChainEntity.Builder.() -> Unit) = modifyEntity(ParentChainEntity.Builder::class.java, entity, modification)
//endregion

@Abstract
interface SimpleAbstractEntity : WorkspaceEntity {

  val parentInList: CompositeAbstractEntity


  //region generated code
  //@formatter:off
  @GeneratedCodeApiVersion(0)
  interface Builder<T: SimpleAbstractEntity>: SimpleAbstractEntity, ModifiableWorkspaceEntity<T>, ObjBuilder<T> {
      override var parentInList: CompositeAbstractEntity
      override var entitySource: EntitySource
  }
  
  companion object: Type<SimpleAbstractEntity, Builder<SimpleAbstractEntity>>() {
      operator fun invoke(entitySource: EntitySource, init: (Builder<SimpleAbstractEntity>.() -> Unit)? = null): SimpleAbstractEntity {
          val builder = builder()
          builder.entitySource = entitySource
          init?.invoke(builder)
          return builder
      }
  }
  //@formatter:on
  //endregion

}

@Abstract
interface CompositeAbstractEntity : SimpleAbstractEntity {
  val children: List<@Child SimpleAbstractEntity>

  val parentEntity: ParentChainEntity?


  //region generated code
  //@formatter:off
  @GeneratedCodeApiVersion(0)
  interface Builder<T: CompositeAbstractEntity>: CompositeAbstractEntity, SimpleAbstractEntity.Builder<T>, ModifiableWorkspaceEntity<T>, ObjBuilder<T> {
      override var parentInList: CompositeAbstractEntity
      override var children: List<SimpleAbstractEntity>
      override var entitySource: EntitySource
      override var parentEntity: ParentChainEntity?
  }
  
  companion object: Type<CompositeAbstractEntity, Builder<CompositeAbstractEntity>>(SimpleAbstractEntity) {
      operator fun invoke(entitySource: EntitySource, init: (Builder<CompositeAbstractEntity>.() -> Unit)? = null): CompositeAbstractEntity {
          val builder = builder()
          builder.entitySource = entitySource
          init?.invoke(builder)
          return builder
      }
  }
  //@formatter:on
  //endregion

}

interface CompositeChildAbstractEntity : CompositeAbstractEntity {

    //region generated code
    //@formatter:off
    @GeneratedCodeApiVersion(0)
    interface Builder: CompositeChildAbstractEntity, CompositeAbstractEntity.Builder<CompositeChildAbstractEntity>, ModifiableWorkspaceEntity<CompositeChildAbstractEntity>, ObjBuilder<CompositeChildAbstractEntity> {
        override var parentInList: CompositeAbstractEntity
        override var children: List<SimpleAbstractEntity>
        override var entitySource: EntitySource
        override var parentEntity: ParentChainEntity?
    }
    
    companion object: Type<CompositeChildAbstractEntity, Builder>(CompositeAbstractEntity) {
        operator fun invoke(entitySource: EntitySource, init: (Builder.() -> Unit)? = null): CompositeChildAbstractEntity {
            val builder = builder()
            builder.entitySource = entitySource
            init?.invoke(builder)
            return builder
        }
    }
    //@formatter:on
    //endregion

}
//region generated code
fun MutableEntityStorage.modifyEntity(entity: CompositeChildAbstractEntity, modification: CompositeChildAbstractEntity.Builder.() -> Unit) = modifyEntity(CompositeChildAbstractEntity.Builder::class.java, entity, modification)
//endregion

interface SimpleChildAbstractEntity : SimpleAbstractEntity {

    //region generated code
    //@formatter:off
    @GeneratedCodeApiVersion(0)
    interface Builder: SimpleChildAbstractEntity, SimpleAbstractEntity.Builder<SimpleChildAbstractEntity>, ModifiableWorkspaceEntity<SimpleChildAbstractEntity>, ObjBuilder<SimpleChildAbstractEntity> {
        override var parentInList: CompositeAbstractEntity
        override var entitySource: EntitySource
    }
    
    companion object: Type<SimpleChildAbstractEntity, Builder>(SimpleAbstractEntity) {
        operator fun invoke(entitySource: EntitySource, init: (Builder.() -> Unit)? = null): SimpleChildAbstractEntity {
            val builder = builder()
            builder.entitySource = entitySource
            init?.invoke(builder)
            return builder
        }
    }
    //@formatter:on
    //endregion

}
//region generated code
fun MutableEntityStorage.modifyEntity(entity: SimpleChildAbstractEntity, modification: SimpleChildAbstractEntity.Builder.() -> Unit) = modifyEntity(SimpleChildAbstractEntity.Builder::class.java, entity, modification)
//endregion