package org.javacs.kt.actions

import org.eclipse.lsp4j.Position
import org.javacs.kt.CompiledFile
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtTypeArgumentList
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection

// TODO: see where this should ideally be placed
private const val DEFAULT_TAB_SIZE = 4

// interfaces are ClassDescriptors by default. When calling AbstractClass super methods, we get a ClassConstructorDescriptor
fun getClassDescriptor(descriptor: DeclarationDescriptor?): ClassDescriptor? =
    descriptor as? ClassDescriptor
        ?: if (descriptor is ClassConstructorDescriptor) {
            descriptor.containingDeclaration
        } else {
            null
        }

fun getSuperClassTypeProjections(
        file: CompiledFile,
        superType: KtSuperTypeListEntry
): List<TypeProjection> =
        superType
                .typeReference
                ?.typeElement
                ?.children
                ?.filter { it is KtTypeArgumentList }
                ?.flatMap { (it as KtTypeArgumentList).arguments }
                ?.mapNotNull {
                    (file.referenceExpressionAtPoint(it?.startOffset ?: 0)?.second as?
                                    ClassDescriptor)
                            ?.defaultType?.asTypeProjection()
                }
                ?: emptyList()

// Checks if the class overrides the given declaration
fun overridesDeclaration(kotlinClass: KtClass, descriptor: MemberDescriptor): Boolean =
    when (descriptor) {
        is FunctionDescriptor -> kotlinClass.declarations.any {
            it.name == descriptor.name.asString()
            && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
            && ((it as? KtNamedFunction)?.let { parametersMatch(it, descriptor) } ?: true)
        }
        is PropertyDescriptor -> kotlinClass.declarations.any {
            it.name == descriptor.name.asString() && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        }
        else -> false
    }

// Checks if two functions have matching parameters
private fun parametersMatch(
        function: KtNamedFunction,
        functionDescriptor: FunctionDescriptor
): Boolean {
    if (function.valueParameters.size != functionDescriptor.valueParameters.size) return false

    for (index in 0 until function.valueParameters.size) {
        if (function.valueParameters[index].name !=
            functionDescriptor.valueParameters[index].name.asString()
        ) {
            return false
        } else if (function.valueParameters[index].typeReference?.typeName() !=
            functionDescriptor.valueParameters[index]
                .type
                .unwrappedType()
                .toString() && function.valueParameters[index].typeReference?.typeName() != null
        ) {
            // Any and Any? seems to be null for Kt* psi objects for some reason? At least for equals
            // TODO: look further into this

            // Note: Since we treat Java overrides as non nullable by default, the above test
            // will fail when the user has made the type nullable.
            // TODO: look into this
            return false
        }
    }

    if (function.typeParameters.size == functionDescriptor.typeParameters.size) {
        for (index in 0 until function.typeParameters.size) {
            if (function.typeParameters[index].variance !=
                functionDescriptor.typeParameters[index].variance
            ) {
                return false
            }
        }
    }

    return true
}

private fun KtTypeReference.typeName(): String? =
        this.name
                ?: this.typeElement
                        ?.children
                        ?.filter { it is KtSimpleNameExpression }
                        ?.map { (it as KtSimpleNameExpression).getReferencedName() }
                        ?.firstOrNull()

fun createFunctionStub(function: FunctionDescriptor): String {
    val name = function.name
    val arguments =
        function.valueParameters.joinToString(", ") { argument ->
            val argumentName = argument.name
            val argumentType = argument.type.unwrappedType()

            "$argumentName: $argumentType"
        }
    val returnType = function.returnType?.unwrappedType()?.toString()?.takeIf { "Unit" != it }

    return "override fun $name($arguments)${returnType?.let { ": $it" } ?: ""} { }"
}

fun createVariableStub(variable: PropertyDescriptor): String {
    val variableType = variable.returnType?.unwrappedType()?.toString()?.takeIf { "Unit" != it }
    return "override val ${variable.name}${variableType?.let { ": $it" } ?: ""} = TODO(\"SET VALUE\")"
}

// about types: regular Kotlin types are marked T or T?, but types from Java are (T..T?) because
// nullability cannot be decided.
// Therefore, we have to unpack in case we have the Java type. Fortunately, the Java types are not
// marked nullable, so we default to non-nullable types. Let the user decide if they want nullable
// types instead. With this implementation Kotlin types also keeps their nullability
private fun KotlinType.unwrappedType(): KotlinType =
        this.unwrap().makeNullableAsSpecified(this.isMarkedNullable)

fun getDeclarationPadding(file: CompiledFile, kotlinClass: KtClass): String {
    // If the class is not empty, the amount of padding is the same as the one in the last
    // declaration of the class
    val paddingSize =
            if (kotlinClass.declarations.isNotEmpty()) {
                val lastFunctionStartOffset = kotlinClass.declarations.last().startOffset
                position(file.content, lastFunctionStartOffset).character
            } else {
                // Otherwise, we just use a default tab size in addition to any existing padding
                // on the class itself (note that the class could be inside another class, for
                // example)
                position(file.content, kotlinClass.startOffset).character + DEFAULT_TAB_SIZE
            }

    return " ".repeat(paddingSize)
}

fun getNewMembersStartPosition(file: CompiledFile, kotlinClass: KtClass): Position? {
    // If the class is not empty, the new member will be put right after the last declaration
    if (kotlinClass.declarations.isNotEmpty()) {
        val lastFunctionEndOffset = kotlinClass.declarations.last().endOffset
        return position(file.content, lastFunctionEndOffset)
    }

    // Otherwise, the member is put at the beginning of the class
    val body = kotlinClass.body
    if (body != null) {
        return position(file.content, body.startOffset + 1)
    }

    // function has no body. We have to create one. New position is right after entire
    // kotlin class text (with space)
    val newPosCorrectLine = position(file.content, kotlinClass.startOffset + 1)
    newPosCorrectLine.character = (kotlinClass.text.length + 2)
    return newPosCorrectLine
}

fun KtClass.hasNoBody() = this.body == null
