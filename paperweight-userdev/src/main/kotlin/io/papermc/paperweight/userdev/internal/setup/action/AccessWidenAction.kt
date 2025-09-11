package io.papermc.paperweight.userdev.internal.setup.action

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.Value
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.action.fileValue
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

fun makeAWTask(name: String, context: SetupHandler.ExecutionContext, dispatcher: WorkDispatcher, javaLauncher: Value<JavaLauncher>, inJar: FileValue) =
    context.userAw?.let {
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