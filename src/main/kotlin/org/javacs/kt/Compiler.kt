@file:OptIn(ExperimentalCompilerApi::class)

package org.javacs.kt

import com.intellij.lang.Language
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.output.writeAllTo
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoots
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.KotlinCodegenFacade
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.container.getService
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.resolve.calls.components.InferenceSession
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingServices
import org.jetbrains.kotlin.util.KotlinFrontEndException
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jetbrains.kotlin.config.CompilerConfiguration as KotlinCompilerConfiguration

/**
 * Kotlin compiler APIs used to parse, analyze and compile
 * files and expressions.
 */
private class CompilationEnvironment(
    javaSourcePath: Set<Path>,
    classPath: Set<Path>
) : Closeable {
    private val disposable = Disposer.newDisposable()

    val environment: KotlinCoreEnvironment = KotlinCoreEnvironment.createForProduction(
        projectDisposable = disposable,
        // Not to be confused with the CompilerConfiguration in the language server Configuration
        configuration = KotlinCompilerConfiguration().apply {
            val langFeatures = mutableMapOf<LanguageFeature, LanguageFeature.State>()
            for (langFeature in LanguageFeature.entries) {
                langFeatures[langFeature] = LanguageFeature.State.ENABLED
            }
            val languageVersionSettings = LanguageVersionSettingsImpl(
                LanguageVersion.LATEST_STABLE,
                ApiVersion.createByLanguageVersion(LanguageVersion.LATEST_STABLE),
                emptyMap(),
                langFeatures
            )

            put(CommonConfigurationKeys.MODULE_NAME, JvmProtoBufUtil.DEFAULT_MODULE_NAME)
            put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, languageVersionSettings)
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, LoggingMessageCollector)
            put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true)

            // configure jvm runtime classpaths
            configureJdkClasspathRoots()

            // Kotlin 1.8.20 requires us to specify the JDK home, otherwise java.* classes won't resolve
            // See https://github.com/JetBrains/kotlin-compiler-server/pull/626
            val jdkHome = File(System.getProperty("java.home")) // TODO Get it from gradle sourceCompatibility
            put(JVMConfigurationKeys.JDK_HOME, jdkHome)

            addJvmClasspathRoots(classPath.map { it.toFile() })
            addJavaSourceRoots(javaSourcePath.map { it.toFile() })
        },
        configFiles = EnvironmentConfigFiles.JVM_CONFIG_FILES
    )
    val parser: KtPsiFactory

    init {
        val project = environment.project
        parser = KtPsiFactory(project)
    }

    fun updateConfiguration(config: Configuration.Compiler) {
        JvmTarget.fromString(config.jvm.target)
            ?.let { environment.configuration.put(JVMConfigurationKeys.JVM_TARGET, it) }
    }

    fun createContainer(sourcePath: Collection<KtFile>): Pair<ComponentProvider, BindingTraceContext> {
        val trace = CliBindingTrace(environment.project)
        val container = TopDownAnalyzerFacadeForJVM.createContainer(
            project = environment.project,
            files = sourcePath,
            trace = trace,
            configuration = environment.configuration,
            packagePartProvider = environment::createPackagePartProvider,
            // TODO FileBasedDeclarationProviderFactory keeps indices, re-use it across calls
            declarationProviderFactory = ::FileBasedDeclarationProviderFactory
        )
        return Pair(container, trace)
    }

    override fun close() {
        Disposer.dispose(disposable)
    }
}

/**
 * Incrementally compiles files and expressions.
 * The basic strategy for compiling one file at-a-time is outlined in OneFilePerformance.
 */
class Compiler(
    javaSourcePath: Set<Path>,
    classPath: Set<Path>,
    private val outputDirectory: File,
) : Closeable {
    private var closed = false

    private val defaultCompileEnvironment = CompilationEnvironment(javaSourcePath, classPath)
    private val compileLock = ReentrantLock() // TODO: Lock at file-level

    companion object {
        init {
            setIdeaIoUseFallback()
        }
    }

    /**
     * Updates the compiler environment using the given
     * configuration (which is a class from this project).
     */
    fun updateConfiguration() {
        defaultCompileEnvironment.updateConfiguration(clientSession.config.compiler)
    }

    fun createPsiFile(content: String, file: Path = Paths.get("dummy.virtual.kt"), language: Language = KotlinLanguage.INSTANCE): PsiFile {
        assert(!content.contains('\r'))

        val new = psiFileFactory().createFileFromText(file.toString(), language, content, true, false)
        assert(new.virtualFile != null)

        return new
    }

    fun createKtFile(content: String, file: Path = Paths.get("dummy.virtual.kt")): KtFile =
        createPsiFile(content, file, language = KotlinLanguage.INSTANCE) as KtFile

    fun psiFileFactory(): PsiFileFactory =
        PsiFileFactory.getInstance(defaultCompileEnvironment.environment.project)

    fun compileKtFile(file: KtFile, sourcePath: Collection<KtFile>): Pair<BindingContext, ModuleDescriptor> =
        compileKtFiles(listOf(file), sourcePath)

    fun compileKtFiles(files: Collection<KtFile>, sourcePath: Collection<KtFile>): Pair<BindingContext, ModuleDescriptor> {
        compileLock.withLock {
            val compileEnv = defaultCompileEnvironment
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
                val compileEnv = defaultCompileEnvironment
                val (container, trace) = compileEnv.createContainer(sourcePath)
                val incrementalCompiler = container.get<ExpressionTypingServices>()
                incrementalCompiler.getTypeInfo(
                        scopeWithImports,
                        expression,
                        TypeUtils.NO_EXPECTED_TYPE,
                        DataFlowInfo.EMPTY,
                        InferenceSession.default,
                        trace,
                        true)
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

    fun generateCode(module: ModuleDescriptor, bindingContext: BindingContext, files: Collection<KtFile>) {
        outputDirectory.takeIf { clientSession.config.codegen.enabled }?.let {
            compileLock.withLock {
                val compileEnv = defaultCompileEnvironment
                val state = GenerationState.Builder(
                    project = compileEnv.environment.project,
                    builderFactory = ClassBuilderFactories.BINARIES,
                    module = module,
                    bindingContext = bindingContext,
                    files = files.toList(),
                    configuration = compileEnv.environment.configuration
                ).build()
                KotlinCodegenFacade.compileCorrectFiles(state)
                state.factory.writeAllTo(it)
            }
        }
    }

    override fun close() {
        if (closed) {
            LOG.warn("Compiler is already closed!")
            return
        }

        defaultCompileEnvironment.close()
        closed = true
    }
}

private object LoggingMessageCollector: MessageCollector {
	override fun clear() {}

	override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
		LOG.debug("Kotlin compiler: [{}] {} @ {}", severity, message, location)
	}

	override fun hasErrors() = false
}
