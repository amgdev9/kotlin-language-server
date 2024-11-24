package org.javacs.kt.index

import org.javacs.kt.LOG
import org.javacs.kt.LanguageClientProgress
import org.javacs.kt.clientSession
import org.javacs.kt.db.MAX_FQNAME_LENGTH
import org.javacs.kt.db.MAX_SHORT_NAME_LENGTH
import org.javacs.kt.db.SymbolEntity
import org.javacs.kt.db.Symbols
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import kotlin.sequences.Sequence

/**
 * A global view of all available symbols across all packages.
 */
class SymbolIndex {
    var progressFactory: LanguageClientProgress.Factory? = null

    /** Rebuilds the entire index. May take a while. */
    fun refresh(module: ModuleDescriptor, exclusions: Sequence<DeclarationDescriptor>) {
        LOG.info("Updating full symbol index...")

        progressFactory?.create("Indexing")?.thenApplyAsync { progress ->
            try {
                transaction(clientSession.db) {
                    // Remove everything first.
                    Symbols.deleteAll()
                    // Add new ones.
                    addDeclarations(allDescriptors(module, exclusions))

                    val count = Symbols.slice(Symbols.fqName.count()).selectAll().first()[Symbols.fqName.count()]
                    LOG.info("Updated full symbol index! (${count} symbol(s))")
                }
            } catch (e: Exception) {
                LOG.error("Error while updating symbol index")
                LOG.printStackTrace(e)
            }

            progress.close()
        }
    }

    // Removes a list of indexes and adds another list. Everything is done in the same transaction.
    fun updateIndexes(remove: Sequence<DeclarationDescriptor>, add: Sequence<DeclarationDescriptor>) {
        LOG.info("Updating symbol index...")

        try {
            transaction(clientSession.db) {
                removeDeclarations(remove)
                addDeclarations(add)

                val count = Symbols.slice(Symbols.fqName.count()).selectAll().first()[Symbols.fqName.count()]
                LOG.info("Updated symbol index! (${count} symbol(s))")
            }
        } catch (e: Exception) {
            LOG.error("Error while updating symbol index")
            LOG.printStackTrace(e)
        }
    }

    private fun removeDeclarations(declarations: Sequence<DeclarationDescriptor>) =
        declarations.forEach { declaration ->
            val (descriptorFqn, extensionReceiverFqn) = getFqNames(declaration)

            if (validFqName(descriptorFqn) && (extensionReceiverFqn?.let { validFqName(it) } != false)) {
                Symbols.deleteWhere {
                    (Symbols.fqName eq descriptorFqn.toString()) and (Symbols.extensionReceiverType eq extensionReceiverFqn?.toString())
                }
            } else {
                LOG.warn("Excluding symbol {} from index since its name is too long", descriptorFqn.toString())
            }
        }

    private fun addDeclarations(declarations: Sequence<DeclarationDescriptor>) =
        declarations.forEach { declaration ->
            val (descriptorFqn, extensionReceiverFqn) = getFqNames(declaration)

            if (validFqName(descriptorFqn) && (extensionReceiverFqn?.let { validFqName(it) } != false)) {
                SymbolEntity.new {
                    fqName = descriptorFqn.toString()
                    shortName = descriptorFqn.shortName().toString()
                    kind = declaration.accept(ExtractSymbolKind, Unit).rawValue
                    visibility = declaration.accept(ExtractSymbolVisibility, Unit).rawValue
                    extensionReceiverType = extensionReceiverFqn?.toString()
                }
            } else {
                LOG.warn("Excluding symbol {} from index since its name is too long", descriptorFqn.toString())
            }
        }

    private fun getFqNames(declaration: DeclarationDescriptor): Pair<FqName, FqName?> {
        val descriptorFqn = declaration.fqNameSafe
        val extensionReceiverFqn = declaration.accept(ExtractSymbolExtensionReceiverType, Unit)?.takeIf { !it.isRoot }

        return Pair(descriptorFqn, extensionReceiverFqn)
    }

    private fun validFqName(fqName: FqName) =
        fqName.toString().length <= MAX_FQNAME_LENGTH
            && fqName.shortName().toString().length <= MAX_SHORT_NAME_LENGTH

    fun query(prefix: String, receiverType: FqName? = null, limit: Int = 20, suffix: String = "%"): List<Symbol> = transaction(clientSession.db) {
        // TODO: Extension completion currently only works if the receiver matches exactly,
        //       ideally this should work with subtypes as well
        SymbolEntity.find {
            (Symbols.shortName like "$prefix$suffix") and (Symbols.extensionReceiverType eq receiverType?.toString())
        }.limit(limit)
            .map { Symbol(
                fqName = FqName(it.fqName),
                kind = Symbol.Kind.fromRaw(it.kind),
                visibility = Symbol.Visibility.fromRaw(it.visibility),
                extensionReceiverType = it.extensionReceiverType?.let(::FqName)
            ) }
    }

    private fun allDescriptors(module: ModuleDescriptor, exclusions: Sequence<DeclarationDescriptor>): Sequence<DeclarationDescriptor> = allPackages(module)
        .map(module::getPackage)
        .flatMap {
            try {
                it.memberScope.getContributedDescriptors(
                    DescriptorKindFilter.ALL
                ) { name -> !exclusions.any { declaration -> declaration.name == name } }
            } catch (_: IllegalStateException) {
                LOG.warn("Could not query descriptors in package $it")
                emptyList()
            }
        }

    private fun allPackages(module: ModuleDescriptor, pkgName: FqName = FqName.ROOT): Sequence<FqName> = module
        .getSubPackagesOf(pkgName) { it.toString() != "META-INF" }
        .asSequence()
        .flatMap { sequenceOf(it) + allPackages(module, it) }
}
