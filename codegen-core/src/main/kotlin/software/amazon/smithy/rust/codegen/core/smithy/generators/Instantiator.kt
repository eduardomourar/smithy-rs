/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.generators

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.ArrayNode
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.NullNode
import software.amazon.smithy.model.node.NumberNode
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.node.StringNode
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.BooleanShape
import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.model.shapes.DocumentShape
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.NumberShape
import software.amazon.smithy.model.shapes.SetShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.TimestampShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.asType
import software.amazon.smithy.rust.codegen.core.rustlang.conditionalBlock
import software.amazon.smithy.rust.codegen.core.rustlang.escape
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.stripOuter
import software.amazon.smithy.rust.codegen.core.rustlang.withBlock
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.isOptional
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.expectMember
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.isStreaming
import software.amazon.smithy.rust.codegen.core.util.letIf

/**
 * Instantiator generates code to instantiate a given Shape given a `Node` representing the value
 *
 * This is primarily used during Protocol test generation
 */
class Instantiator(
    private val symbolProvider: RustSymbolProvider,
    private val model: Model,
    private val runtimeConfig: RuntimeConfig,
    private val target: CodegenTarget,
) {
    data class Ctx(
        // The Rust HTTP library lower cases headers but Smithy protocol tests
        // contain httpPrefix headers with uppercase keys
        val lowercaseMapKeys: Boolean,
        val streaming: Boolean,
        // Whether we are instantiating with a Builder, in which case all setters take Option
        val builder: Boolean,
        // Fill out `required` fields with a default value.
        val defaultsForRequiredFields: Boolean,
    )

    companion object {
        fun defaultContext() = Ctx(lowercaseMapKeys = false, streaming = false, builder = false, defaultsForRequiredFields = false)
    }

    fun render(
        writer: RustWriter,
        shape: Shape,
        arg: Node,
        ctx: Ctx = defaultContext(),
    ) {
        when (shape) {
            // Compound Shapes
            is StructureShape -> renderStructure(writer, shape, arg as ObjectNode, ctx.copy(builder = true))
            is UnionShape -> renderUnion(writer, shape, arg as ObjectNode, ctx)

            // Collections
            is ListShape -> renderList(writer, shape, arg as ArrayNode, ctx)
            is MapShape -> renderMap(writer, shape, arg as ObjectNode, ctx)
            is SetShape -> renderSet(writer, shape, arg as ArrayNode, ctx)

            // Members, supporting potentially optional members
            is MemberShape -> renderMember(writer, shape, arg, ctx)

            // Wrapped Shapes
            is TimestampShape -> writer.write(
                "#T::from_secs(${(arg as NumberNode).value})",
                RuntimeType.DateTime(runtimeConfig),
            )

            /**
             * ```rust
             * Blob::new("arg")
             * ```
             */
            is BlobShape -> if (ctx.streaming) {
                writer.write(
                    "#T::from_static(b${(arg as StringNode).value.dq()})",
                    RuntimeType.ByteStream(runtimeConfig),
                )
            } else {
                writer.write(
                    "#T::new(${(arg as StringNode).value.dq()})",
                    RuntimeType.Blob(runtimeConfig),
                )
            }

            // Simple Shapes
            is StringShape -> renderString(writer, shape, arg as StringNode)
            is NumberShape -> when (arg) {
                is StringNode -> {
                    val numberSymbol = symbolProvider.toSymbol(shape)
                    // support Smithy custom values, such as Infinity
                    writer.rust(
                        """<#T as #T>::parse_smithy_primitive(${arg.value.dq()}).expect("invalid string for number")""",
                        numberSymbol,
                        CargoDependency.SmithyTypes(runtimeConfig).asType().member("primitive::Parse"),
                    )
                }
                is NumberNode -> writer.write(arg.value)
            }
            is BooleanShape -> writer.write(arg.asBooleanNode().get().toString())
            is DocumentShape -> writer.rustBlock("") {
                val smithyJson = CargoDependency.smithyJson(runtimeConfig).asType()
                rustTemplate(
                    """
                    let json_bytes = br##"${Node.prettyPrintJson(arg)}"##;
                    let mut tokens = #{json_token_iter}(json_bytes).peekable();
                    #{expect_document}(&mut tokens).expect("well formed json")
                    """,
                    "expect_document" to smithyJson.member("deserialize::token::expect_document"),
                    "json_token_iter" to smithyJson.member("deserialize::json_token_iter"),
                )
            }
            else -> writer.writeWithNoFormatting("todo!() /* $shape $arg */")
        }
    }

    /**
     * If the shape is optional: `Some(inner)` or `None`
     * otherwise: `inner`
     */
    private fun renderMember(writer: RustWriter, shape: MemberShape, arg: Node, ctx: Ctx) {
        val target = model.expectShape(shape.target)
        val symbol = symbolProvider.toSymbol(shape)
        if (arg is NullNode) {
            check(
                symbol.isOptional(),
            ) { "A null node was provided for $shape but the symbol was not optional. This is invalid input data." }
            writer.write("None")
        } else {
            writer.conditionalBlock(
                "Some(", ")",
                conditional = ctx.builder || symbol.isOptional(),
            ) {
                writer.conditionalBlock(
                    "Box::new(",
                    ")",
                    conditional = symbol.rustType().stripOuter<RustType.Option>() is RustType.Box,
                ) {
                    render(
                        this,
                        target,
                        arg,
                        ctx.copy(builder = false)
                            .letIf(shape.getMemberTrait(model, HttpPrefixHeadersTrait::class.java).isPresent) {
                                it.copy(lowercaseMapKeys = true)
                            }.letIf(shape.isStreaming(model)) {
                                it.copy(streaming = true)
                            },
                    )
                }
            }
        }
    }

    private fun renderSet(writer: RustWriter, shape: SetShape, data: ArrayNode, ctx: Ctx) {
        renderList(writer, shape, data, ctx)
    }

    /**
     * ```rust
     * {
     *      let mut ret = HashMap::new();
     *      ret.insert("k", ...);
     *      ret.insert("k2", ...);
     *      ret
     * }
     */
    private fun renderMap(writer: RustWriter, shape: MapShape, data: ObjectNode, ctx: Ctx) {
        if (data.members.isEmpty()) {
            writer.write("#T::new()", RustType.HashMap.RuntimeType)
        } else {
            writer.rustBlock("") {
                write("let mut ret = #T::new();", RustType.HashMap.RuntimeType)
                for ((key, value) in data.members) {
                    withBlock("ret.insert(", ");") {
                        renderMember(this, shape.key, key, ctx)
                        when (ctx.lowercaseMapKeys) {
                            true -> rust(".to_ascii_lowercase(), ")
                            else -> rust(", ")
                        }
                        renderMember(this, shape.value, value, ctx)
                    }
                }
                write("ret")
            }
        }
    }

    /**
     * ```rust
     * MyUnion::Variant(...)
     * ```
     */
    private fun renderUnion(writer: RustWriter, shape: UnionShape, data: ObjectNode, ctx: Ctx) {
        val unionSymbol = symbolProvider.toSymbol(shape)

        val variant = if (ctx.defaultsForRequiredFields && data.members.isEmpty()) {
            val (name, memberShape) = shape.allMembers.entries.first()
            val targetShape = model.expectShape(memberShape.target)
            Node.from(name) to fillDefaultValue(targetShape)
        } else {
            check(data.members.size == 1)
            val entry = data.members.iterator().next()
            entry.key to entry.value
        }

        val memberName = variant.first.value
        val member = shape.expectMember(memberName)
        writer.write("#T::${symbolProvider.toMemberName(member)}", unionSymbol)
        // unions should specify exactly one member
        writer.withBlock("(", ")") {
            renderMember(this, member, variant.second, ctx)
        }
    }

    /**
     * ```rust
     * vec![..., ..., ...]
     * ```
     */
    private fun renderList(writer: RustWriter, shape: CollectionShape, data: ArrayNode, ctx: Ctx) {
        writer.withBlock("vec![", "]") {
            data.elements.forEach { v ->
                renderMember(this, shape.member, v, ctx)
                write(",")
            }
        }
    }

    private fun renderString(writer: RustWriter, shape: StringShape, arg: StringNode) {
        val data = writer.escape(arg.value).dq()
        if (!shape.hasTrait<EnumTrait>()) {
            writer.rust("$data.to_string()")
        } else {
            val enumSymbol = symbolProvider.toSymbol(shape)
            if (target == CodegenTarget.SERVER) {
                writer.rust("""#T::try_from($data).expect("This is used in tests ONLY")""", enumSymbol)
            } else {
                writer.rust("#T::from($data)", enumSymbol)
            }
        }
    }

    /**
     * ```rust
     * MyStruct::builder().field_1("hello").field_2(5).build()
     * ```
     */
    private fun renderStructure(writer: RustWriter, shape: StructureShape, data: ObjectNode, ctx: Ctx) {
        fun renderMemberHelper(memberShape: MemberShape, value: Node) {
            writer.withBlock(".${memberShape.setterName()}(", ")") {
                renderMember(this, memberShape, value, ctx)
            }
        }

        writer.write("#T::builder()", symbolProvider.toSymbol(shape))
        if (ctx.defaultsForRequiredFields) {
            shape.allMembers.entries
                .filter { (name, memberShape) ->
                    memberShape.isRequired && !data.members.containsKey(Node.from(name))
                }
                .forEach { (_, memberShape) ->
                    renderMemberHelper(memberShape, fillDefaultValue(memberShape))
                }
        }

        data.members.forEach { (key, value) ->
            val memberShape = shape.expectMember(key.value)
            renderMemberHelper(memberShape, value)
        }
        writer.write(".build()")
        if (StructureGenerator.fallibleBuilder(shape, symbolProvider)) {
            writer.write(".unwrap()")
        }
    }

    /**
     * Returns a default value for a shape.
     *
     * Warning: this method does not take into account any constraint traits attached to the shape.
     */
    private fun fillDefaultValue(shape: Shape): Node = when (shape) {
        is MemberShape -> fillDefaultValue(model.expectShape(shape.target))

        // Aggregate shapes.
        is StructureShape -> Node.objectNode()
        is UnionShape -> Node.objectNode()
        is CollectionShape -> Node.arrayNode()
        is MapShape -> Node.objectNode()

        // Simple Shapes
        is TimestampShape -> Node.from(0) // Number node for timestamp
        is BlobShape -> Node.from("") // String node for bytes
        is StringShape -> Node.from("")
        is NumberShape -> Node.from(0)
        is BooleanShape -> Node.from(false)
        is DocumentShape -> Node.objectNode()
        else -> throw CodegenException("Unrecognized shape `$shape`")
    }
}
