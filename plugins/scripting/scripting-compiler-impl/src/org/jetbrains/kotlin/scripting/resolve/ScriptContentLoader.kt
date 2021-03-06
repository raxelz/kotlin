/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.resolve

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.KotlinScriptDefinition
import java.io.File
import kotlin.reflect.KClass
import kotlin.script.dependencies.ScriptContents
import kotlin.script.experimental.dependencies.DependenciesResolver
import kotlin.script.experimental.dependencies.DependenciesResolver.ResolveResult.Failure
import kotlin.script.experimental.dependencies.ScriptDependencies
import kotlin.script.experimental.dependencies.ScriptReport

class ScriptContentLoader(private val project: Project) {
    fun getScriptContents(scriptDefinition: KotlinScriptDefinition, file: VirtualFile)
            = BasicScriptContents(
        file,
        getAnnotations = { loadAnnotations(scriptDefinition, file) })

    private fun loadAnnotations(scriptDefinition: KotlinScriptDefinition, file: VirtualFile): List<Annotation> {
        val classLoader = scriptDefinition.template.java.classLoader
        // TODO_R: report error on failure to load annotation class
        return ApplicationManager.getApplication().runReadAction<List<Annotation>> {
            getAnnotationEntries(file, project)
                .mapNotNull { psiAnn ->
                    // TODO: consider advanced matching using semantic similar to actual resolving
                    scriptDefinition.acceptedAnnotations.find { ann ->
                        psiAnn.typeName.let { it == ann.simpleName || it == ann.qualifiedName }
                    }?.let {
                        @Suppress("UNCHECKED_CAST")
                        constructAnnotation(
                            psiAnn,
                            classLoader.loadClass(it.qualifiedName).kotlin as KClass<out Annotation>,
                            project
                        )
                    }
                }
        }
    }

    private fun getAnnotationEntries(file: VirtualFile, project: Project): Iterable<KtAnnotationEntry> {
        val psiFile: PsiFile = PsiManager.getInstance(project).findFile(file)
                               ?: throw IllegalArgumentException("Unable to load PSI from ${file.canonicalPath}")
        return (psiFile as? KtFile)?.annotationEntries
               ?: throw IllegalArgumentException("Unable to extract kotlin annotations from ${file.name} (${file.fileType})")
    }

    class BasicScriptContents(virtualFile: VirtualFile, getAnnotations: () -> Iterable<Annotation>) : ScriptContents {
        override val file: File = File(virtualFile.path)
        override val annotations: Iterable<Annotation> by lazy(LazyThreadSafetyMode.PUBLICATION) { getAnnotations() }
        override val text: CharSequence? by lazy(LazyThreadSafetyMode.PUBLICATION) { virtualFile.inputStream.reader(charset = virtualFile.charset).readText() }
    }

    fun loadContentsAndResolveDependencies(
        scriptDef: KotlinScriptDefinition,
        file: VirtualFile
    ): DependenciesResolver.ResolveResult {
        val scriptContents = getScriptContents(scriptDef, file)
        val environment = getEnvironment(scriptDef)
        val result = try {
            scriptDef.dependencyResolver.resolve(
                    scriptContents,
                    environment
            )
        }
        catch (e: Throwable) {
            e.asResolveFailure(scriptDef)
        }
        return result
    }

    fun getEnvironment(scriptDef: KotlinScriptDefinition) =
            (scriptDef as? KotlinScriptDefinitionFromAnnotatedTemplate)?.environment.orEmpty()
}

fun ScriptDependencies.adjustByDefinition(
        scriptDef: KotlinScriptDefinition
): ScriptDependencies {
    val additionalClasspath = (scriptDef as? KotlinScriptDefinitionFromAnnotatedTemplate)?.templateClasspath ?: return this
    if (additionalClasspath.isEmpty()) return this
    return copy(classpath = additionalClasspath + classpath)
}

fun Throwable.asResolveFailure(scriptDef: KotlinScriptDefinition): Failure {
    val prefix = "${scriptDef.dependencyResolver::class.simpleName} threw exception ${this::class.simpleName}:\n "
    return Failure(ScriptReport(prefix + (message ?: "<no message>"), ScriptReport.Severity.FATAL))
}