/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2022 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.papermc.paperweight.userdev

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.papermc.paperweight.userdev.ZipUtils.unpack
import io.papermc.paperweight.userdev.ZipUtils.unpackNullable
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.util.Arrays
import java.util.Objects
import java.util.function.Supplier
import net.fabricmc.tinyremapper.FileSystemReference

object FileSystemUtil {
    @Throws(IOException::class)
    fun getJarFileSystem(path: Path, create: Boolean): Delegate {
        return Delegate(FileSystemReference.openJar(path, create))
    }

    class Delegate(private val reference: FileSystemReference) : AutoCloseable, Supplier<FileSystem> {

        fun getPath(path: String?, vararg more: String?): Path {
            return get().getPath(path, *more)
        }

        @Throws(IOException::class)
        fun readAllBytes(path: String?): ByteArray {
            val fsPath = getPath(path)

            if (Files.exists(fsPath)) {
                return Files.readAllBytes(fsPath)
            } else {
                throw NoSuchFileException(fsPath.toString())
            }
        }

        @Throws(IOException::class)
        override fun close() {
            reference.close()
        }

        override fun get(): FileSystem {
            return reference.fs!!
        }
    }
}

object ZipUtils {
    @Throws(IOException::class)
    fun replace(zip: Path, path: String, bytes: ByteArray) {
        FileSystemUtil.getJarFileSystem(zip, true).use { fs ->
            val fsPath: Path = fs.get().getPath(path)
            if (Files.exists(fsPath)) {
                Files.write(fsPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            } else {
                throw NoSuchFileException(fsPath.toString())
            }
        }
    }

    @Throws(IOException::class)
    fun unpackNullable(zip: Path, path: String?): ByteArray? {
        return try {
            unpack(zip, path)
        } catch (e: NoSuchFileException) {
            null
        }
    }

    @Throws(IOException::class)
    fun unpack(zip: Path, path: String?): ByteArray {
        FileSystemUtil.getJarFileSystem(zip, false).use { fs ->
            return fs.readAllBytes(path)
        }
    }
}

data class AccessWidenerFile(
    val path: String,
    val modId: String,
    val content: ByteArray
) {
    override fun hashCode(): Int {
        var result = Objects.hash(path, modId)
        result = 31 * result + content.contentHashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AccessWidenerFile

        if (path != other.path) return false
        if (modId != other.modId) return false
        if (!content.contentEquals(other.content)) return false

        return true
    }

    companion object {
        /**
         * Reads the access-widener contained in a mod jar, or returns null if there is none.
         */
        fun fromModJar(modJarPath: Path): AccessWidenerFile? {
            val modJsonBytes: ByteArray?

            try {
                modJsonBytes = unpackNullable(modJarPath, "fabric.mod.json")
            } catch (e: IOException) {
                throw UncheckedIOException("Failed to read access-widener file from: " + modJarPath.toAbsolutePath(), e)
            }

            if (modJsonBytes == null) {
                return null
            }

            val jsonObject: JsonObject = Gson().fromJson(String(modJsonBytes, StandardCharsets.UTF_8), JsonObject::class.java)

            if (!jsonObject.has("accessWidener")) {
                return null
            }

            val awPath: String = jsonObject.get("accessWidener").asString
            val modId: String = jsonObject.get("id").asString

            val content: ByteArray

            try {
                content = unpack(modJarPath, awPath)
            } catch (e: IOException) {
                throw UncheckedIOException(
                    String.format(
                        "Could not find access widener file (%s) defined in the fabric.mod.json file of %s",
                        awPath,
                        modJarPath.toAbsolutePath()
                    ), e
                )
            }

            return AccessWidenerFile(
                awPath,
                modId,
                content
            )
        }
    }
}