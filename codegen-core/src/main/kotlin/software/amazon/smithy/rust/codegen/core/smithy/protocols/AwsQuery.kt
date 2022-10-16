/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols

import software.amazon.smithy.aws.traits.protocols.AwsQueryErrorTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.pattern.UriPattern
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ToShapeId
import software.amazon.smithy.model.traits.HttpTrait
import software.amazon.smithy.model.traits.TimestampFormatTrait
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.asType
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.protocols.parse.AwsQueryParserGenerator
import software.amazon.smithy.rust.codegen.core.smithy.protocols.parse.StructuredDataParserGenerator
import software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize.AwsQuerySerializerGenerator
import software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize.StructuredDataSerializerGenerator
import software.amazon.smithy.rust.codegen.core.util.getTrait

private val awsQueryHttpTrait = HttpTrait.builder()
    .code(200)
    .method("POST")
    .uri(UriPattern.parse("/"))
    .build()

class AwsQueryBindingResolver(private val model: Model) :
    StaticHttpBindingResolver(model, awsQueryHttpTrait, "application/x-www-form-urlencoded", "text/xml") {
    override fun errorCode(errorShape: ToShapeId): String {
        val error = model.expectShape(errorShape.toShapeId())
        return error.getTrait<AwsQueryErrorTrait>()?.code ?: errorShape.toShapeId().name
    }
}

class AwsQueryProtocol(private val codegenContext: CodegenContext) : Protocol {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val awsQueryErrors: RuntimeType = RuntimeType.wrappedXmlErrors(runtimeConfig)
    private val errorScope = arrayOf(
        "Bytes" to RuntimeType.Bytes,
        "Error" to RuntimeType.GenericError(runtimeConfig),
        "HeaderMap" to RuntimeType.http.member("HeaderMap"),
        "Response" to RuntimeType.http.member("Response"),
        "XmlError" to CargoDependency.smithyXml(runtimeConfig).asType().member("decode::XmlError"),
    )
    private val xmlDeserModule = RustModule.private("xml_deser")

    override val httpBindingResolver: HttpBindingResolver = AwsQueryBindingResolver(codegenContext.model)

    override val defaultTimestampFormat: TimestampFormatTrait.Format = TimestampFormatTrait.Format.DATE_TIME

    override fun structuredDataParser(operationShape: OperationShape): StructuredDataParserGenerator =
        AwsQueryParserGenerator(codegenContext, awsQueryErrors)

    override fun structuredDataSerializer(operationShape: OperationShape): StructuredDataSerializerGenerator =
        AwsQuerySerializerGenerator(codegenContext)

    override fun parseHttpGenericError(operationShape: OperationShape): RuntimeType =
        RuntimeType.forInlineFun("parse_http_generic_error", xmlDeserModule) { writer ->
            writer.rustBlockTemplate(
                "pub fn parse_http_generic_error(response: &#{Response}<#{Bytes}>) -> Result<#{Error}, #{XmlError}>",
                *errorScope,
            ) {
                rust("#T::parse_generic_error(response.body().as_ref())", awsQueryErrors)
            }
        }

    override fun parseEventStreamGenericError(operationShape: OperationShape): RuntimeType =
        RuntimeType.forInlineFun("parse_event_stream_generic_error", xmlDeserModule) { writer ->
            writer.rustBlockTemplate(
                "pub fn parse_event_stream_generic_error(payload: &#{Bytes}) -> Result<#{Error}, #{XmlError}>",
                *errorScope,
            ) {
                rust("#T::parse_generic_error(payload.as_ref())", awsQueryErrors)
            }
        }
}
