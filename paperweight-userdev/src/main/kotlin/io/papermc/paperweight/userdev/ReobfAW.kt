package io.papermc.paperweight.userdev;


import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.ZipUtils.replace
import java.io.File
import java.io.IOException
import java.nio.file.Path
import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerRemapper
import net.fabricmc.accesswidener.AccessWidenerWriter
import net.fabricmc.tinyremapper.TinyUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

open class RemapAccessWidenerTask : DefaultTask() {
    @TaskAction
    @Throws(IOException::class)
    fun remapAccessWidenerTask() {
        val reobfTask = project.tasks.getByName("reobfJar") as RemapJar
        val inputJar = reobfTask.outputJar.get().asFile
        val paths = reobfTask.remapClasspath.files.stream().map(File::toPath).toArray { n -> arrayOfNulls<Path>(n); }
        val accessWidenerFile = AccessWidenerFile.fromModJar(inputJar.toPath()) ?: throw RuntimeException("Please run reobfJar Task!")
        val input: ByteArray = accessWidenerFile.content
        val mappingsFile = reobfTask.mappingsFile
        val tinyRemapper = net.fabricmc.tinyremapper.TinyRemapper.newRemapper()
            .withMappings(TinyUtils.createTinyMappingProvider(mappingsFile.get().asFile.toPath(), "mojang+yarn", "spigot")).build()
        tinyRemapper.readInputs(inputJar.toPath())
        tinyRemapper.readClassPath(*paths)
        val version: Int = AccessWidenerReader.readVersion(input)
        val writer = AccessWidenerWriter(version)
        val remapper = AccessWidenerRemapper(writer, tinyRemapper.getEnvironment().remapper, "named", "intermediary")
        val reader = AccessWidenerReader(remapper)
        reader.read(input)
        val output: ByteArray = writer.write()
        replace(inputJar.toPath(), accessWidenerFile.path, output)
    }
}