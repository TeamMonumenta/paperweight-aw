/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight

import java.io.Reader
import java.io.Writer
import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerVisitor
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.ModifierChange
import org.cadixdev.at.io.AccessTransformFormat
import org.cadixdev.bombe.type.signature.MethodSignature

private fun awToAtAccess(aw: AccessWidenerReader.AccessType): AccessTransform {
    return when (aw) {
        AccessWidenerReader.AccessType.ACCESSIBLE -> AccessTransform.of(AccessChange.PUBLIC)
        AccessWidenerReader.AccessType.MUTABLE -> AccessTransform.of(ModifierChange.REMOVE)
        AccessWidenerReader.AccessType.EXTENDABLE -> AccessTransform.of(ModifierChange.REMOVE)
    }
}

private class ATSetVisitor(val set: AccessTransformSet) : AccessWidenerVisitor {
    override fun visitClass(name: String, access: AccessWidenerReader.AccessType, transitive: Boolean) {
        val clazz = name.replace("/", ".")
        val classSet = set.getOrCreateClass(clazz)
        classSet.merge(awToAtAccess(access))
    }

    override fun visitMethod(owner: String, name: String, descriptor: String, access: AccessWidenerReader.AccessType, transitive: Boolean) {
        val clazz = owner.replace("/", ".")
        val classSet = set.getOrCreateClass(clazz)
        classSet.mergeMethod(MethodSignature.of(name, descriptor), awToAtAccess(access))
    }

    override fun visitField(owner: String, name: String, descriptor: String, access: AccessWidenerReader.AccessType, transitive: Boolean) {
        val clazz = owner.replace("/", ".")
        val classSet = set.getOrCreateClass(clazz)
        classSet.mergeField(name, awToAtAccess(access))
    }
}

class AWAccessTransformer : AccessTransformFormat {
    override fun read(reader: Reader, set: AccessTransformSet) {
        AccessWidenerReader(ATSetVisitor(set)).read(reader.buffered())
    }

    override fun write(writer: Writer, set: AccessTransformSet) {
        TODO("not implemented yet")
    }
}

val AW_ACCESS_TRANSFORMER: AccessTransformFormat = AWAccessTransformer()
