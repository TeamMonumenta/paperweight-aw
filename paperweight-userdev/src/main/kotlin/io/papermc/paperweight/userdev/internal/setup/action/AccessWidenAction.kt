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

package io.papermc.paperweight.userdev.internal.setup.action

import io.papermc.paperweight.tasks.applyAccessTransform
import io.papermc.paperweight.userdev.internal.action.*
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.workers.WorkerExecutor

class AccessWidenAction(
    @Input private val javaLauncher: Value<JavaLauncher>,
    private val workerExecutor: WorkerExecutor,
    @Input val aw: FileValue,
    @Input private val inputJar: FileValue,
    @Output val outputJar: FileValue,
) : WorkDispatcher.Action {
    override fun execute() {
        applyAccessTransform(
            inputJarPath = inputJar.get(),
            outputJarPath = outputJar.get(),
            atFilePath = aw.get(),
            workerExecutor = workerExecutor,
            launcher = javaLauncher.get(),
            format = "fabric"
        ).await()
    }
}

fun makeAWTask(
    name: String,
    context: SetupHandler.ExecutionContext,
    dispatcher: WorkDispatcher,
    javaLauncher: Value<JavaLauncher>,
    inJar: FileValue
) = context.userAw?.let {
    val aw = dispatcher.register(
        name,
        AccessWidenAction(
            javaLauncher,
            context.workerExecutor,
            fileValue(it),
            inJar,
            dispatcher.outputFile("output.jar"),
        )
    )

    dispatcher.provided(aw.aw)

    aw.outputJar
} ?: inJar
