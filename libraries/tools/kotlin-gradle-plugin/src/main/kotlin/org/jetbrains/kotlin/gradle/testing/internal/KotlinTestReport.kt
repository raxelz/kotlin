/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.testing.internal

import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.*
import java.io.File
import java.net.URI

open class KotlinTestReport : TestReport() {
    @Internal
    val testTasks = mutableListOf<AbstractTestTask>()

    @Internal
    var parent: KotlinTestReport? = null

    @Internal
    val children = mutableListOf<KotlinTestReport>()

    @Input
    var checkFailedTests: Boolean = false

    @Input
    var ignoreFailures: Boolean = false

    private var hasFailedTests = false

    private val failedTestsListener = object : TestListener {
        override fun beforeTest(testDescriptor: TestDescriptor) {
        }

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        }

        override fun beforeSuite(suite: TestDescriptor) {
        }

        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            if (result.failedTestCount > 0) {
                hasFailedTests = true
            }
        }
    }

    fun registerTestTask(task: AbstractTestTask) {
        testTasks.add(task)
        task.addTestListener(failedTestsListener)

        @Suppress("UnstableApiUsage")
        reportOn(task.binResultsDir)
    }

    fun registerChild(child: KotlinTestReport) {
        children.add(child)
    }

    open val htmlReportUrl: String?
        @Internal get() = destinationDir?.let { asClickableFileUrl(it.resolve("index.html")) }

    private fun asClickableFileUrl(path: File): String {
        return URI("file", "", path.toURI().path, null, null).toString()
    }

    @TaskAction
    fun checkFailedTests() {
        if (checkFailedTests && hasFailedTests) {
            val message = StringBuilder("There were failing tests.")

            val reportUrl = htmlReportUrl
            if (reportUrl != null) {
                message.append(" See the report at: $reportUrl")
            }

            if (ignoreFailures) {
                logger.warn(message.toString())
            } else {
                throw GradleException(message.toString())
            }
        }
    }

    fun overrideReporting() {
        testTasks.forEach {
            overrideReporting(it)
        }

        children.forEach {
            it.overrideReporting()
        }
    }

    protected open fun overrideReporting(task: AbstractTestTask) {
        task.ignoreFailures = true
        checkFailedTests = true
        ignoreFailures = false

        @Suppress("UnstableApiUsage")
        task.reports.html.isEnabled = false
        @Suppress("UnstableApiUsage")
        task.reports.junitXml.isEnabled = false
    }
}
