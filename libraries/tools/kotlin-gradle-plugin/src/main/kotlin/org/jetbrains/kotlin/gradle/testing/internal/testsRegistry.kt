/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.testing.internal

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.plugin.TaskHolder
import org.jetbrains.kotlin.gradle.tasks.createOrRegisterTask
import org.jetbrains.kotlin.gradle.tasks.locateTask

internal val Project.allTestsTask: TaskHolder<KotlinTestReport>
    get() = getOrCreateAggregatedTestTask(
        name = "allTests",
        description = "Runs the tests for all targets and create aggregated report",
        reportName = "all"
    ) {
        tasks.maybeCreate(LifecycleBasePlugin.CHECK_TASK_NAME)
            .dependsOn(it.getTaskOrProvider())
    }

internal fun Project.getOrCreateAggregatedTestTask(
    name: String,
    description: String,
    reportName: String,
    parent: KotlinTestReport? = null,
    configure: (TaskHolder<KotlinTestReport>) -> Unit = {}
): TaskHolder<KotlinTestReport> {
    val existed = locateTask<KotlinTestReport>(name)
    if (existed != null) return existed

    val aggregate: TaskHolder<KotlinTestReport> = createOrRegisterTask(name) { aggregate ->
        aggregate.description = description
        aggregate.group = JavaBasePlugin.VERIFICATION_GROUP

        aggregate.destinationDir = reportsDir.resolve(reportName)
        aggregate.parent = parent

        if (System.getProperty("idea.active") != null) {
            aggregate.extensions.extraProperties.set("idea.internal.test", true)
        }

        project.gradle.taskGraph.whenReady { graph ->
            if (graph.hasTask(aggregate) && (parent == null || !graph.hasTask(parent))) {
                // when [aggregate] task enabled and there is no parent aggregate or it is not executed,
                // test failure should be reported only on [aggregate],
                // not at individual target's test tasks. To do that, we need:
                // - disable all reporting in test tasks
                // - enable [checkFailedTests] on [aggregate]

                aggregate.overrideReporting()
            }
        }

        parent?.registerChild(aggregate)
    }

    parent?.dependsOn(aggregate.getTaskOrProvider())

    configure(aggregate)

    return aggregate
}

private fun cleanTaskName(taskName: String): String {
    check(taskName.isNotEmpty())
    return "clean" + taskName.capitalize()
}

private val Project.cleanAllTestTask: Task
    get() {
        val taskName = cleanTaskName(allTestsTask.name)
        return tasks.findByName(taskName)
            ?: tasks.create(taskName, Task::class.java).also {
                tasks.getByName(LifecycleBasePlugin.CLEAN_TASK_NAME).dependsOn(it)
            }
    }

@Suppress("UnstableApiUsage")
internal fun registerTestTask(taskHolder: TaskHolder<AbstractTestTask>) {
    registerTestTaskInAggregate(taskHolder, taskHolder.project.allTestsTask.doGetTask())
}

internal fun registerTestTaskInAggregate(
    taskHolder: TaskHolder<AbstractTestTask>,
    allTests: KotlinTestReport
) {
    val project = taskHolder.project

    project.tasks.getByName(LifecycleBasePlugin.CHECK_TASK_NAME).dependsOn(taskHolder.name)
    project.cleanAllTestTask.dependsOn(cleanTaskName(taskHolder.name))
    allTests.dependsOn(taskHolder.name)

    taskHolder.configure { task ->
        allTests.registerTestTask(task)
        ijListenTestTask(task)
    }
}