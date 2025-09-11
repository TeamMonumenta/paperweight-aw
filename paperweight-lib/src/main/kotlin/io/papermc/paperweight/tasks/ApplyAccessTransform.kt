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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.io.Reader
import java.io.Writer
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerVisitor
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.ModifierChange
import org.cadixdev.at.io.AccessTransformFormat
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.atlas.Atlas
import org.cadixdev.atlas.AtlasTransformerContext
import org.cadixdev.bombe.analysis.InheritanceProvider
import org.cadixdev.bombe.asm.analysis.ClassProviderInheritanceProvider
import org.cadixdev.bombe.asm.jar.ClassProvider
import org.cadixdev.bombe.jar.JarClassEntry
import org.cadixdev.bombe.jar.JarEntryTransformer
import org.cadixdev.bombe.type.signature.MethodSignature
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

fun applyAccessTransform(
    inputJarPath: Path,
    outputJarPath: Path,
    atFilePath: Path,
    jvmArgs: List<String> = listOf("-Xmx1G"),
    workerExecutor: WorkerExecutor,
    launcher: JavaLauncher,
    format: String = "forge"
): WorkQueue {
    ensureParentExists(outputJarPath)
    ensureDeleted(outputJarPath)

    val queue = workerExecutor.processIsolation {
        forkOptions.jvmArgs(jvmArgs)
        forkOptions.executable(launcher.executablePath.path.absolutePathString())
    }

    queue.submit(ApplyAccessTransform.AtlasAction::class) {
        inputJar.set(inputJarPath)
        atFile.set(atFilePath)
        outputJar.set(outputJarPath)
        atFormat.set(format)
    }

    return queue
}

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

val atFormatByName = mapOf(
    "forge" to AccessTransformFormats.FML,
    "fabric" to object : AccessTransformFormat {
        override fun read(reader: Reader, set: AccessTransformSet) {
            AccessWidenerReader(ATSetVisitor(set)).read(reader.buffered())
        }

        override fun write(writer: Writer, set: AccessTransformSet) {
            TODO("not implemented yet")
        }
    }
)

@CacheableTask
abstract class ApplyAccessTransform : JavaLauncherTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val atFile: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx1G"))
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        applyAccessTransform(
            inputJarPath = inputJar.path,
            outputJarPath = outputJar.path,
            atFilePath = atFile.path,
            jvmArgs = jvmargs.get(),
            workerExecutor = workerExecutor,
            launcher = launcher.get()
        )
    }

    abstract class AtlasAction : WorkAction<AtlasParameters> {
        override fun execute() {
            val at = atFormatByName[parameters.atFormat.get()]!!.read(parameters.atFile.path)

            Atlas().apply {
                install {
                    // Replace the inheritance provider to set ASM9 opcodes
                    val inheritanceProvider = AtlasTransformerContext::class.java.getDeclaredField("inheritanceProvider")
                    inheritanceProvider.isAccessible = true
                    val classProvider = ClassProviderInheritanceProvider::class.java.getDeclaredField("provider")
                    classProvider.isAccessible = true
                    inheritanceProvider.set(
                        it,
                        ClassProviderInheritanceProvider(
                            Opcodes.ASM9,
                            classProvider.get(inheritanceProvider.get(it)) as ClassProvider
                        )
                    )
                    // End replace inheritance provider

                    AtJarEntryTransformer(it, at)
                }
                run(parameters.inputJar.path, parameters.outputJar.path)
            }
        }
    }

    interface AtlasParameters : WorkParameters {
        val inputJar: RegularFileProperty
        val atFile: RegularFileProperty
        val outputJar: RegularFileProperty
        val atFormat: Property<String>
    }
}

class AtJarEntryTransformer(
    private val context: AtlasTransformerContext,
    private val at: AccessTransformSet
) : JarEntryTransformer {
    override fun transform(entry: JarClassEntry): JarClassEntry {
        val reader = ClassReader(entry.contents)
        val writer = ClassWriter(reader, 0)
        reader.accept(AccessTransformerVisitor(at, context.inheritanceProvider(), writer), 0)
        return JarClassEntry(entry.name, entry.time, writer.toByteArray())
    }
}

class AccessTransformerVisitor(
    private val at: AccessTransformSet,
    private val inheritanceProvider: InheritanceProvider,
    writer: ClassWriter
) : ClassVisitor(Opcodes.ASM9, writer) {

    private lateinit var classTransform: AccessTransformSet.Class

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        classTransform = completedClassAt(name)
        super.visit(version, classTransform.get().apply(access), name, signature, superName, interfaces)
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        return super.visitField(classTransform.getField(name).apply(access), name, descriptor, signature, value)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val newAccess = classTransform.getMethod(MethodSignature.of(name, descriptor)).apply(access)
        return super.visitMethod(newAccess, name, descriptor, signature, exceptions)
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
        super.visitInnerClass(name, outerName, innerName, completedClassAt(name).get().apply(access))
    }

    private fun completedClassAt(className: String): AccessTransformSet.Class = synchronized(at) {
        at.getOrCreateClass(className).apply { complete(inheritanceProvider) }
    }
}

fun AccessTransform?.apply(currentModifier: Int): Int {
    if (this == null) {
        return currentModifier
    }
    var value = currentModifier
    if (this.access != AccessChange.NONE) {
        value = value and AsmUtil.RESET_ACCESS
        value = value or this.access.modifier
    }
    when (this.final) {
        ModifierChange.REMOVE -> {
            value = value and Opcodes.ACC_FINAL.inv()
        }
        ModifierChange.ADD -> {
            value = value or Opcodes.ACC_FINAL
        }
        else -> {
        }
    }
    return value
}
