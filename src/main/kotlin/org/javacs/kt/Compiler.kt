package org.javacs.kt

import com.intellij.lang.Language
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.output.writeAllTo
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.KotlinCodegenFacade
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.container.getService
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.resolve.calls.components.InferenceSession
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingServices
import org.jetbrains.kotlin.util.KotlinFrontEndException
import java.io.File
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Incrementally compiles files and expressions.
 * The basic strategy for compiling one file at-a-time is outlined in OneFilePerformance.
 */
class Compiler(
    private val outputDirectory: File,
) {
    private val disposable = Disposer.newDisposable()
    private val compileEnvironment = buildKotlinCoreEnvironment(disposable)
    private val compileLock = ReentrantLock() // TODO: Lock at file-level

    companion object {
        init {
            setIdeaIoUseFallback()
        }
    }

    fun createPsiFile(content: String, file: Path, language: Language): PsiFile {
        assert(!content.contains('\r'))

        val psiFileFactory = PsiFileFactory.getInstance(compileEnvironment.project)
        val new = psiFileFactory.createFileFromText(file.toString(), language, content, true, false)
        assert(new.virtualFile != null)

        return new
    }

    fun createKtFile(content: String, file: Path): KtFile =
        createPsiFile(content, file, language = KotlinLanguage.INSTANCE) as KtFile

    fun compileKtFile(file: KtFile, sourcePath: Collection<KtFile>): Pair<BindingContext, ModuleDescriptor> =
        compileKtFiles(listOf(file), sourcePath)

    fun compileKtFiles(files: Collection<KtFile>, sourcePath: Collection<KtFile>): Pair<BindingContext, ModuleDescriptor> {
        compileLock.withLock {
            val compileEnv = compileEnvironment
            val (container, trace) = compileEnv.createContainer(sourcePath)
            val module = container.getService(ModuleDescriptor::class.java)
            container.get<LazyTopDownAnalyzer>().analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files)
            return Pair(trace.bindingContext, module)
        }
    }

    fun compileKtExpression(expression: KtExpression, scopeWithImports: LexicalScope, sourcePath: Collection<KtFile>): Pair<BindingContext, ComponentProvider> {
        try {
            // Use same lock as 'compileFile' to avoid concurrency issues
            compileLock.withLock {
                val (container, trace) = compileEnvironment.createContainer(sourcePath)
                val incrementalCompiler = container.get<ExpressionTypingServices>()
                incrementalCompiler.getTypeInfo(
                    scopeWithImports,
                    expression,
                    TypeUtils.NO_EXPECTED_TYPE,
                    DataFlowInfo.EMPTY,
                    InferenceSession.default,
                    trace,
                    true
                )
                return Pair(trace.bindingContext, container)
            }
        } catch (e: KotlinFrontEndException) {
            throw RuntimeException("Error while analyzing: ${expression.text}", e)
        }
    }

    fun removeGeneratedCode(files: Collection<KtFile>) {
        files.forEach { file ->
            file.declarations.forEach { declaration ->
                outputDirectory.resolve(
                    file.packageFqName.asString().replace(".", File.separator) + File.separator + declaration.name + ".class"
                ).delete()
            }
        }
    }

    fun generateCode(module: ModuleDescriptor, bindingContext: BindingContext, files: List<KtFile>) {
        return  // TODO This crashes, test it
        compileLock.withLock {
            val state = GenerationState.Builder(
                project = compileEnvironment.project,
                builderFactory = ClassBuilderFactories.BINARIES,
                module = module,
                bindingContext = bindingContext,
                files = files,
                configuration = compileEnvironment.configuration
            ).build()
            KotlinCodegenFacade.compileCorrectFiles(state)
            state.factory.writeAllTo(outputDirectory)
        }
    }

    fun close() {
        disposable.dispose()
    }
}
