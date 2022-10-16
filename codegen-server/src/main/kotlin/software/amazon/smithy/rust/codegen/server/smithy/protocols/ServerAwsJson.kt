/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.protocols

import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.escape
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.generators.protocol.ProtocolSupport
import software.amazon.smithy.rust.codegen.core.smithy.protocols.AwsJsonVersion
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpBindingResolver
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolGeneratorFactory
import software.amazon.smithy.rust.codegen.core.smithy.protocols.awsJsonFieldName
import software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize.JsonCustomization
import software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize.JsonSection
import software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize.JsonSerializerGenerator
import software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize.StructuredDataSerializerGenerator
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerAwsJsonProtocol
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol

/**
 * AwsJson 1.0 and 1.1 server-side protocol factory. This factory creates the [ServerHttpBoundProtocolGenerator]
 * with AwsJson specific configurations.
 */
class ServerAwsJsonFactory(private val version: AwsJsonVersion) :
    ProtocolGeneratorFactory<ServerHttpBoundProtocolGenerator, ServerCodegenContext> {
    override fun protocol(codegenContext: ServerCodegenContext): ServerProtocol = ServerAwsJsonProtocol(codegenContext, version)

    override fun buildProtocolGenerator(codegenContext: ServerCodegenContext): ServerHttpBoundProtocolGenerator =
        ServerHttpBoundProtocolGenerator(codegenContext, protocol(codegenContext))

    override fun support(): ProtocolSupport {
        return ProtocolSupport(
            /* Client support */
            requestSerialization = false,
            requestBodySerialization = false,
            responseDeserialization = false,
            errorDeserialization = false,
            /* Server support */
            requestDeserialization = true,
            requestBodyDeserialization = true,
            responseSerialization = true,
            errorSerialization = true,
        )
    }
}

/**
 * AwsJson requires errors to be serialized in server responses with an additional `__type` field. This
 * customization writes the right field depending on the version of the AwsJson protocol.
 */
class ServerAwsJsonError(private val awsJsonVersion: AwsJsonVersion) : JsonCustomization() {
    override fun section(section: JsonSection): Writable = when (section) {
        is JsonSection.ServerError -> writable {
            if (section.structureShape.hasTrait<ErrorTrait>()) {
                val typeId = when (awsJsonVersion) {
                    // AwsJson 1.0 wants the whole shape ID (namespace#Shape).
                    // https://awslabs.github.io/smithy/1.0/spec/aws/aws-json-1_0-protocol.html#operation-error-serialization
                    AwsJsonVersion.Json10 -> section.structureShape.id.toString()
                    // AwsJson 1.1 wants only the shape name (Shape).
                    // https://awslabs.github.io/smithy/1.0/spec/aws/aws-json-1_1-protocol.html#operation-error-serialization
                    AwsJsonVersion.Json11 -> section.structureShape.id.name.toString()
                }
                rust("""${section.jsonObject}.key("__type").string("${escape(typeId)}");""")
            }
        }
    }
}

/**
 * AwsJson requires operation errors to be serialized in server response with an additional `__type` field. This class
 * customizes [JsonSerializerGenerator] to add this functionality.
 *
 * https://awslabs.github.io/smithy/1.0/spec/aws/aws-json-1_0-protocol.html#operation-error-serialization
 */
class ServerAwsJsonSerializerGenerator(
    private val codegenContext: CodegenContext,
    private val httpBindingResolver: HttpBindingResolver,
    private val awsJsonVersion: AwsJsonVersion,
    private val jsonSerializerGenerator: JsonSerializerGenerator =
        JsonSerializerGenerator(
            codegenContext,
            httpBindingResolver,
            ::awsJsonFieldName,
            customizations = listOf(ServerAwsJsonError(awsJsonVersion)),
        ),
) : StructuredDataSerializerGenerator by jsonSerializerGenerator
