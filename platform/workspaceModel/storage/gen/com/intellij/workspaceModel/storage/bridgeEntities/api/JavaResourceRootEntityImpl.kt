package com.intellij.workspaceModel.storage.bridgeEntities.api

import com.intellij.workspaceModel.storage.EntityInformation
import com.intellij.workspaceModel.storage.EntitySource
import com.intellij.workspaceModel.storage.EntityStorage
import com.intellij.workspaceModel.storage.GeneratedCodeApiVersion
import com.intellij.workspaceModel.storage.GeneratedCodeImplVersion
import com.intellij.workspaceModel.storage.ModifiableWorkspaceEntity
import com.intellij.workspaceModel.storage.MutableEntityStorage
import com.intellij.workspaceModel.storage.WorkspaceEntity
import com.intellij.workspaceModel.storage.impl.ConnectionId
import com.intellij.workspaceModel.storage.impl.ExtRefKey
import com.intellij.workspaceModel.storage.impl.ModifiableWorkspaceEntityBase
import com.intellij.workspaceModel.storage.impl.WorkspaceEntityBase
import com.intellij.workspaceModel.storage.impl.WorkspaceEntityData
import com.intellij.workspaceModel.storage.impl.extractOneToManyParent
import com.intellij.workspaceModel.storage.impl.updateOneToManyParentOfChild
import com.intellij.workspaceModel.storage.url.VirtualFileUrl
import org.jetbrains.deft.ObjBuilder
import org.jetbrains.deft.Type
import org.jetbrains.deft.annotations.Child

@GeneratedCodeApiVersion(0)
@GeneratedCodeImplVersion(0)
open class JavaResourceRootEntityImpl: JavaResourceRootEntity, WorkspaceEntityBase() {
    
    companion object {
        internal val SOURCEROOT_CONNECTION_ID: ConnectionId = ConnectionId.create(SourceRootEntity::class.java, JavaResourceRootEntity::class.java, ConnectionId.ConnectionType.ONE_TO_MANY, false)
    }
        
    override val sourceRoot: SourceRootEntity
        get() = snapshot.extractOneToManyParent(SOURCEROOT_CONNECTION_ID, this)!!           
        
    override var generated: Boolean = false
    @JvmField var _relativeOutputPath: String? = null
    override val relativeOutputPath: String
        get() = _relativeOutputPath!!

    class Builder(val result: JavaResourceRootEntityData?): ModifiableWorkspaceEntityBase<JavaResourceRootEntity>(), JavaResourceRootEntity.Builder {
        constructor(): this(JavaResourceRootEntityData())
        
        override fun applyToBuilder(builder: MutableEntityStorage) {
            if (this.diff != null) {
                if (existsInBuilder(builder)) {
                    this.diff = builder
                    return
                }
                else {
                    error("Entity JavaResourceRootEntity is already created in a different builder")
                }
            }
            
            this.diff = builder
            this.snapshot = builder
            addToBuilder()
            this.id = getEntityData().createEntityId()
            
            // Process entities from extension fields
            val keysToRemove = ArrayList<ExtRefKey>()
            for ((key, entity) in extReferences) {
                if (!key.isChild()) {
                    continue
                }
                if (entity is List<*>) {
                    for (item in entity) {
                        if (item is ModifiableWorkspaceEntityBase<*>) {
                            builder.addEntity(item)
                        }
                    }
                    entity as List<WorkspaceEntity>
                    val (withBuilder_entity, woBuilder_entity) = entity.partition { it is ModifiableWorkspaceEntityBase<*> && it.diff != null }
                    applyRef(key.getConnectionId(), withBuilder_entity)
                    keysToRemove.add(key)
                }
                else {
                    entity as WorkspaceEntity
                    builder.addEntity(entity)
                    applyRef(key.getConnectionId(), entity)
                    keysToRemove.add(key)
                }
            }
            for (key in keysToRemove) {
                extReferences.remove(key)
            }
            
            // Adding parents and references to the parent
            val __sourceRoot = _sourceRoot
            if (__sourceRoot != null && (__sourceRoot is ModifiableWorkspaceEntityBase<*>) && __sourceRoot.diff == null) {
                builder.addEntity(__sourceRoot)
            }
            if (__sourceRoot != null && (__sourceRoot is ModifiableWorkspaceEntityBase<*>) && __sourceRoot.diff != null) {
                // Set field to null (in referenced entity)
                val __mutJavaResourceRoots = (__sourceRoot as SourceRootEntityImpl.Builder)._javaResourceRoots?.toMutableList()
                __mutJavaResourceRoots?.remove(this)
                __sourceRoot._javaResourceRoots = if (__mutJavaResourceRoots.isNullOrEmpty()) emptyList() else __mutJavaResourceRoots
            }
            if (__sourceRoot != null) {
                applyParentRef(SOURCEROOT_CONNECTION_ID, __sourceRoot)
                this._sourceRoot = null
            }
            val parentKeysToRemove = ArrayList<ExtRefKey>()
            for ((key, entity) in extReferences) {
                if (key.isChild()) {
                    continue
                }
                if (entity is List<*>) {
                    error("Cannot have parent lists")
                }
                else {
                    entity as WorkspaceEntity
                    builder.addEntity(entity)
                    applyParentRef(key.getConnectionId(), entity)
                    parentKeysToRemove.add(key)
                }
            }
            for (key in parentKeysToRemove) {
                extReferences.remove(key)
            }
            checkInitialization() // TODO uncomment and check failed tests
        }
    
        fun checkInitialization() {
            val _diff = diff
            if (_diff != null) {
                if (_diff.extractOneToManyParent<WorkspaceEntityBase>(SOURCEROOT_CONNECTION_ID, this) == null) {
                    error("Field JavaResourceRootEntity#sourceRoot should be initialized")
                }
            }
            else {
                if (_sourceRoot == null) {
                    error("Field JavaResourceRootEntity#sourceRoot should be initialized")
                }
            }
            if (!getEntityData().isEntitySourceInitialized()) {
                error("Field JavaResourceRootEntity#entitySource should be initialized")
            }
            if (!getEntityData().isRelativeOutputPathInitialized()) {
                error("Field JavaResourceRootEntity#relativeOutputPath should be initialized")
            }
        }
    
        
        var _sourceRoot: SourceRootEntity? = null
        override var sourceRoot: SourceRootEntity
            get() {
                val _diff = diff
                return if (_diff != null) {
                    _diff.extractOneToManyParent(SOURCEROOT_CONNECTION_ID, this) ?: _sourceRoot!!
                } else {
                    _sourceRoot!!
                }
            }
            set(value) {
                checkModificationAllowed()
                val _diff = diff
                if (_diff != null && value is ModifiableWorkspaceEntityBase<*> && value.diff == null) {
                    // Back reference for the list of non-ext field
                    if (value is SourceRootEntityImpl.Builder) {
                        value._javaResourceRoots = (value._javaResourceRoots ?: emptyList()) + this
                    }
                    // else you're attaching a new entity to an existing entity that is not modifiable
                    _diff.addEntity(value)
                }
                if (_diff != null && (value !is ModifiableWorkspaceEntityBase<*> || value.diff != null)) {
                    _diff.updateOneToManyParentOfChild(SOURCEROOT_CONNECTION_ID, this, value)
                }
                else {
                    // Back reference for the list of non-ext field
                    if (value is SourceRootEntityImpl.Builder) {
                        value._javaResourceRoots = (value._javaResourceRoots ?: emptyList()) + this
                    }
                    // else you're attaching a new entity to an existing entity that is not modifiable
                    
                    this._sourceRoot = value
                }
                changedProperty.add("sourceRoot")
            }
        
        override var entitySource: EntitySource
            get() = getEntityData().entitySource
            set(value) {
                checkModificationAllowed()
                getEntityData().entitySource = value
                changedProperty.add("entitySource")
                
            }
            
        override var generated: Boolean
            get() = getEntityData().generated
            set(value) {
                checkModificationAllowed()
                getEntityData().generated = value
                changedProperty.add("generated")
            }
            
        override var relativeOutputPath: String
            get() = getEntityData().relativeOutputPath
            set(value) {
                checkModificationAllowed()
                getEntityData().relativeOutputPath = value
                changedProperty.add("relativeOutputPath")
            }
        
        override fun getEntityData(): JavaResourceRootEntityData = result ?: super.getEntityData() as JavaResourceRootEntityData
        override fun getEntityClass(): Class<JavaResourceRootEntity> = JavaResourceRootEntity::class.java
    }
}
    
class JavaResourceRootEntityData : WorkspaceEntityData<JavaResourceRootEntity>() {
    var generated: Boolean = false
    lateinit var relativeOutputPath: String

    
    fun isRelativeOutputPathInitialized(): Boolean = ::relativeOutputPath.isInitialized

    override fun wrapAsModifiable(diff: MutableEntityStorage): ModifiableWorkspaceEntity<JavaResourceRootEntity> {
        val modifiable = JavaResourceRootEntityImpl.Builder(null)
        modifiable.allowModifications {
          modifiable.diff = diff
          modifiable.snapshot = diff
          modifiable.id = createEntityId()
          modifiable.entitySource = this.entitySource
        }
        return modifiable
    }

    override fun createEntity(snapshot: EntityStorage): JavaResourceRootEntity {
        val entity = JavaResourceRootEntityImpl()
        entity.generated = generated
        entity._relativeOutputPath = relativeOutputPath
        entity.entitySource = entitySource
        entity.snapshot = snapshot
        entity.id = createEntityId()
        return entity
    }

    override fun getEntityInterface(): Class<out WorkspaceEntity> {
        return JavaResourceRootEntity::class.java
    }

    override fun serialize(ser: EntityInformation.Serializer) {
    }

    override fun deserialize(de: EntityInformation.Deserializer) {
    }

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (this::class != other::class) return false
        
        other as JavaResourceRootEntityData
        
        if (this.entitySource != other.entitySource) return false
        if (this.generated != other.generated) return false
        if (this.relativeOutputPath != other.relativeOutputPath) return false
        return true
    }

    override fun equalsIgnoringEntitySource(other: Any?): Boolean {
        if (other == null) return false
        if (this::class != other::class) return false
        
        other as JavaResourceRootEntityData
        
        if (this.generated != other.generated) return false
        if (this.relativeOutputPath != other.relativeOutputPath) return false
        return true
    }

    override fun hashCode(): Int {
        var result = entitySource.hashCode()
        result = 31 * result + generated.hashCode()
        result = 31 * result + relativeOutputPath.hashCode()
        return result
    }
}