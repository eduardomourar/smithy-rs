/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.smithy

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EnumDefinition
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.rust.codegen.rustlang.Attribute
import software.amazon.smithy.rust.codegen.rustlang.RustMetadata
import software.amazon.smithy.rust.codegen.rustlang.Visibility
import software.amazon.smithy.rust.codegen.util.hasTrait

/**
 * Default delegator to enable easily decorating another symbol provider.
 */
open class WrappingSymbolProvider(private val base: RustSymbolProvider) : RustSymbolProvider {
    override fun config(): SymbolVisitorConfig {
        return base.config()
    }

    override fun toEnumVariantName(definition: EnumDefinition): MaybeRenamed? {
        return base.toEnumVariantName(definition)
    }

    override fun toSymbol(shape: Shape): Symbol {
        return base.toSymbol(shape)
    }

    override fun toMemberName(shape: MemberShape): String {
        return base.toMemberName(shape)
    }
}

/**
 * Attach `meta` to symbols. `meta` is used by the generators (e.g. StructureGenerator) to configure the generated models.
 *
 * Protocols may inherit from this class and override the `xyzMeta` methods to modify structure generation.
 */
abstract class SymbolMetadataProvider(private val base: RustSymbolProvider) : WrappingSymbolProvider(base) {
    override fun toSymbol(shape: Shape): Symbol {
        val baseSymbol = base.toSymbol(shape)
        val meta = when (shape) {
            is MemberShape -> memberMeta(shape)
            is StructureShape -> structureMeta(shape)
            is UnionShape -> unionMeta(shape)
            is StringShape -> if (shape.hasTrait<EnumTrait>()) {
                enumMeta(shape)
            } else null
            else -> null
        }
        return baseSymbol.toBuilder().meta(meta).build()
    }

    abstract fun memberMeta(memberShape: MemberShape): RustMetadata
    abstract fun structureMeta(structureShape: StructureShape): RustMetadata
    abstract fun unionMeta(unionShape: UnionShape): RustMetadata
    abstract fun enumMeta(stringShape: StringShape): RustMetadata
}

/**
 * The base metadata supports a list of attributes that are used by generators to decorate code.
 * By default we apply #[non_exhaustive] only to client structures since model changes should
 * be considered as breaking only when generating server code.
 */
class BaseSymbolMetadataProvider(
    base: RustSymbolProvider,
    additionalAttributes: List<Attribute>,
) : SymbolMetadataProvider(base) {
    private val containerDefault = RustMetadata(
        Attribute.Derives(defaultDerives.toSet()),
        additionalAttributes = additionalAttributes,
        visibility = Visibility.PUBLIC
    )

    override fun memberMeta(memberShape: MemberShape): RustMetadata {
        return RustMetadata(visibility = Visibility.PUBLIC)
    }

    override fun structureMeta(structureShape: StructureShape): RustMetadata {
        return containerDefault
    }

    override fun unionMeta(unionShape: UnionShape): RustMetadata {
        return containerDefault
    }

    override fun enumMeta(stringShape: StringShape): RustMetadata {
        return containerDefault.withDerives(
            RuntimeType.std.member("hash::Hash")
        ).withDerives( // enums can be eq because they can only contain strings
            RuntimeType.std.member("cmp::Eq"),
            // enums can be Ord because they can only contain strings
            RuntimeType.std.member("cmp::PartialOrd"),
            RuntimeType.std.member("cmp::Ord")
        )
    }

    companion object {
        private val defaultDerives by lazy {
            with(RuntimeType) {
                listOf(Debug, PartialEq, Clone)
            }
        }
    }
}

private const val MetaKey = "meta"
fun Symbol.Builder.meta(rustMetadata: RustMetadata?): Symbol.Builder {
    return this.putProperty(MetaKey, rustMetadata)
}

fun Symbol.expectRustMetadata(): RustMetadata = this.getProperty(MetaKey, RustMetadata::class.java).orElseThrow {
    CodegenException(
        "Expected $this to have metadata attached but it did not. "
    )
}
