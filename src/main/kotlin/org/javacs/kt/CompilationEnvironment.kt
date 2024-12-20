package org.javacs.kt

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoots
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import java.io.File
import org.jetbrains.kotlin.config.CompilerConfiguration as KotlinCompilerConfiguration

fun buildKotlinCoreEnvironment(disposable: Disposable) = KotlinCoreEnvironment.createForProduction(
    projectDisposable = disposable,
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
        put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_21)    // TODO Make this version configurable by build system

        // configure jvm runtime classpaths
        configureJdkClasspathRoots()

        // Kotlin 1.8.20 requires us to specify the JDK home, otherwise java.* classes won't resolve
        // See https://github.com/JetBrains/kotlin-compiler-server/pull/626
        val jdkHome = File(System.getProperty("java.home")) // TODO Get it from gradle sourceCompatibility
        put(JVMConfigurationKeys.JDK_HOME, jdkHome)

        addJvmClasspathRoots(clientSession.projectClasspath.classPath.map { it.compiledJar.toFile() })
        addJavaSourceRoots(clientSession.projectClasspath.javaSourceDirs.map { it.toFile() })
    },
    configFiles = EnvironmentConfigFiles.JVM_CONFIG_FILES
)

fun KotlinCoreEnvironment.createContainer(sourcePath: Collection<KtFile>): Pair<ComponentProvider, BindingTraceContext> {
    val trace = CliBindingTrace(project)
    val container = TopDownAnalyzerFacadeForJVM.createContainer(
        project = project,
        files = sourcePath,
        trace = trace,
        configuration = configuration,
        packagePartProvider = ::createPackagePartProvider,
        // TODO FileBasedDeclarationProviderFactory keeps indices, re-use it across calls
        declarationProviderFactory = ::FileBasedDeclarationProviderFactory
    )
    return Pair(container, trace)
}

private object LoggingMessageCollector: MessageCollector {
    override fun clear() {}

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
        LOG.debug("Kotlin compiler: [{}] {} @ {}", severity, message, location)
    }

    override fun hasErrors() = false
}
