package com.intellij.workspaceModel.codegen.classes

import com.intellij.workspaceModel.storage.MutableEntityStorage
import com.intellij.workspaceModel.storage.WorkspaceEntity
import com.intellij.workspaceModel.storage.bridgeEntities.api.LibraryEntity
import com.intellij.workspaceModel.storage.impl.ExtRefKey
import com.intellij.workspaceModel.storage.impl.ModifiableWorkspaceEntityBase
import com.intellij.workspaceModel.codegen.*
import com.intellij.workspaceModel.codegen.*
import com.intellij.workspaceModel.codegen.fields.implWsBuilderFieldCode
import com.intellij.workspaceModel.codegen.fields.implWsBuilderIsInitializedCode
import com.intellij.workspaceModel.codegen.fields.refsConnectionId
import com.intellij.workspaceModel.codegen.utils.fqn
import com.intellij.workspaceModel.codegen.utils.lines
import com.intellij.workspaceModel.codegen.deft.ObjType
import com.intellij.workspaceModel.codegen.deft.TList
import com.intellij.workspaceModel.codegen.deft.TRef
import com.intellij.workspaceModel.codegen.deft.ExtField
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

fun ObjType<*, *>.implWsEntityBuilderCode(): String {
  return """
    class Builder(val result: $javaDataName?): ${ModifiableWorkspaceEntityBase::class.fqn}<$javaFullName>(), $javaBuilderName {
        constructor(): this($javaDataName())
        
${
    lines("        ") {
      section("override fun applyToBuilder(builder: ${MutableEntityStorage::class.fqn})") {
        `if`("this.diff != null") {
          ifElse("existsInBuilder(builder)", {
            line("this.diff = builder")
            line("return")
          }) {
            line("error(\"Entity $javaFullName is already created in a different builder\")")
          }
        }
        line()
        line("this.diff = builder")
        line("this.snapshot = builder")
        line("addToBuilder()")
        line("this.id = getEntityData().createEntityId()")
        line()
        list(structure.vfuFields) {
          val suffix = if (type is TList<*>) ".toHashSet()" else ""
          "index(this, \"$javaName\", this.$javaName$suffix)"
        }
        if (name == LibraryEntity::class.simpleName) {
          line("indexLibraryRoots(${LibraryEntity::roots.name})")
        }
        listBuilder(structure.allRefsFields.filter { it.type.isRefTypeWithoutList() && it.type.getRefType().child }) {
          val tmpFieldName = "_${it.implFieldName}"
          line("val $tmpFieldName = ${it.implFieldName}")
          `if`("$tmpFieldName != null && $tmpFieldName is ${ModifiableWorkspaceEntityBase::class.fqn}<*>") {
            line("builder.addEntity($tmpFieldName)")
            line("applyRef(${it.refsConnectionId}, $tmpFieldName)")
            line("this.${it.implFieldName} = null")
          }
        }
        listBuilder(
          structure.allRefsFields.filter { it.type.let { it is TList<*> && it.elementType.let { internalType -> internalType is TRef<*> && internalType.child } } }) {
          val tmpFieldName = "_${it.implFieldName}"
          line("val $tmpFieldName = ${it.implFieldName}!!")
          section("for (item in $tmpFieldName)") {
            `if`("item is ${ModifiableWorkspaceEntityBase::class.fqn}<*>") {
              line("builder.addEntity(item)")
            }
          }
          line("val (withBuilder_${it.name}, woBuilder_${it.name}) = $tmpFieldName.partition { it is ${ModifiableWorkspaceEntityBase::class.fqn}<*> && it.diff != null }")
          line("applyRef(${it.refsConnectionId}, withBuilder_${it.name})")
          line("this.${it.implFieldName} = if (woBuilder_${it.name}.isNotEmpty()) woBuilder_${it.name} else emptyList()")
        }
        lineComment("Process entities from extension fields")
        line("val keysToRemove = ArrayList<${ExtRefKey::class.fqn}>()")
        `for`("(key, entity) in extReferences") {
          `if`("!key.isChild()") {
            line("continue")
          }
          `if`("entity is List<*>") {
            section("for (item in entity)") {
              `if`("item is ${ModifiableWorkspaceEntityBase::class.fqn}<*>") {
                line("builder.addEntity(item)")
              }
            }
            line("entity as List<WorkspaceEntity>")
            line("val (withBuilder_entity, woBuilder_entity) = entity.partition { it is ${ModifiableWorkspaceEntityBase::class.fqn}<*> && it.diff != null }")
            line("applyRef(key.getConnectionId(), withBuilder_entity)")
            line("keysToRemove.add(key)")
            //                        line("this.entity = if (woBuilder_entity.isNotEmpty()) woBuilder_entity else null")
          }
          `else` {
            line("entity as ${WorkspaceEntity::class.fqn}")
            line("builder.addEntity(entity)")
            line("applyRef(key.getConnectionId(), entity)")
            line("keysToRemove.add(key)")
          }
        }
        `for`("key in keysToRemove") {
          line("extReferences.remove(key)")
        }

        line("")
        line("// Adding parents and references to the parent")
        // Update parents
        listBuilder(structure.allRefsFields.filter { it.type.isRefType() && it.type.getRefType().child.not() }) {
          val tmpFieldName = "_${it.implFieldName}"
          line("val $tmpFieldName = ${it.implFieldName}")
          `if`("$tmpFieldName != null && ($tmpFieldName is ${ModifiableWorkspaceEntityBase::class.fqn}<*>) && $tmpFieldName.diff == null") {
            line("builder.addEntity($tmpFieldName)")
          }
          `if`("$tmpFieldName != null && ($tmpFieldName is ${ModifiableWorkspaceEntityBase::class.fqn}<*>) && $tmpFieldName.diff != null") {
            lineComment("Set field to null (in referenced entity)")
            val parentField = it.getParentField()
            val tempPropertyName = "__mut${parentField.name.replaceFirstChar {
              if (it.isLowerCase()) it.titlecase(Locale.getDefault())
              else it.toString()
            }}"
            if (parentField !is ExtField<*, *>) {
              if (parentField.type.isList()) {
                if (parentField.owner.abstract) {
                  line(
                    "val access = $tmpFieldName::class.${fqn(KClass<*>::memberProperties)}.single { it.name == \"_${parentField.name}\" } as ${KMutableProperty1::class.fqn}<*, *>"
                  )
                  line("val $tempPropertyName = (access.getter.call($tmpFieldName) as? List<*>)?.toMutableList()")
                  line("$tempPropertyName?.remove(this)")
                  line("access.setter.call($tmpFieldName, if ($tempPropertyName.isNullOrEmpty()) null else $tempPropertyName)")
                }
                else {
                  line(
                    "val $tempPropertyName = ($tmpFieldName as ${parentField.owner.name}Impl.Builder)._${parentField.name}?.toMutableList()")
                  line("$tempPropertyName?.remove(this)")
                  line("$tmpFieldName._${parentField.name} = if ($tempPropertyName.isNullOrEmpty()) emptyList() else $tempPropertyName")
                }
              }
              else {
                if (parentField.owner.abstract) {
                  line(
                    "val access = $tmpFieldName::class.${fqn(KClass<*>::memberProperties)}.single { it.name == \"_${parentField.name}\" } as ${KMutableProperty1::class.fqn}<*, *>"
                  )
                  line("access.setter.call($tmpFieldName, null)")
                }
                else {
                  line("($tmpFieldName as ${parentField.owner.name}Impl.Builder)._${parentField.name} = null")
                }
              }
            }
            else {
              val key = "${ExtRefKey::class.fqn}(\"${it.owner.name}\", \"${it.name}\", true, ${it.refsConnectionId})"
              if (parentField.type.isList()) {
                line(
                  "val $tempPropertyName = (($tmpFieldName as ${ModifiableWorkspaceEntityBase::class.fqn}<*>).extReferences[$key] as? List<Any> ?: emptyList()).toMutableList()")
                line("$tempPropertyName.remove(this)")
                line("$tmpFieldName.extReferences[$key] = $tempPropertyName")
              }
              else {
                line("$tmpFieldName.extReferences.remove($key)")
              }
            }
          }
          `if`("$tmpFieldName != null") {
            line("applyParentRef(${it.refsConnectionId}, _${it.implFieldName})")
            line("this.${it.implFieldName} = null")
          }
        }

        line("val parentKeysToRemove = ArrayList<${ExtRefKey::class.fqn}>()")
        `for`("(key, entity) in extReferences") {
          `if`("key.isChild()") {
            line("continue")
          }
          `if`("entity is List<*>") {
            line("error(\"Cannot have parent lists\")")
          }
          `else` {
            line("entity as ${WorkspaceEntity::class.fqn}")
            line("builder.addEntity(entity)")
            line("applyParentRef(key.getConnectionId(), entity)")
            line("parentKeysToRemove.add(key)")
          }
        }
        `for`("key in parentKeysToRemove") {
          line("extReferences.remove(key)")
        }

        line("checkInitialization() // TODO uncomment and check failed tests")
      }
      result.append("\n")

      section("fun checkInitialization()") {
        line("val _diff = diff")
        list(structure.allFields.noPersistentId().noOptional().noDefaultValue()) { lineBuilder, field ->
          lineBuilder.implWsBuilderIsInitializedCode(field)
        }
      }
      
      if (name == LibraryEntity::class.simpleName) {
        section("private fun indexLibraryRoots(libraryRoots: List<LibraryRoot>)") {
          line("val jarDirectories = mutableSetOf<VirtualFileUrl>()")
          line("val libraryRootList = libraryRoots.map {")
          line("  if (it.inclusionOptions != LibraryRoot.InclusionOptions.ROOT_ITSELF) {")
          line("    jarDirectories.add(it.url)")
          line("  }")
          line("  it.url")
          line("}.toHashSet()")
          line("index(this, \"roots\", libraryRootList)")
          line("indexJarDirectories(this, jarDirectories)")
        }
      }
    }
  }
        
        ${structure.allFields.filter { it.name != "persistentId" }.lines("        ") { implWsBuilderFieldCode }.trimEnd()}
        
        override fun getEntityData(): $javaDataName${if (abstract) "<T>" else ""} = result ?: super.getEntityData() as $javaDataName${if (abstract) "<T>" else ""}
        override fun getEntityClass(): Class<$javaFullName> = $javaFullName::class.java
    }
    """.trimIndent()
}

