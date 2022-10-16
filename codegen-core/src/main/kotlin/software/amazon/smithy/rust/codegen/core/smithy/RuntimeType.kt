/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.traits.TimestampFormatTrait
import software.amazon.smithy.rust.codegen.core.Version
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.CratesIo
import software.amazon.smithy.rust.codegen.core.rustlang.DependencyLocation
import software.amazon.smithy.rust.codegen.core.rustlang.DependencyScope
import software.amazon.smithy.rust.codegen.core.rustlang.InlineDependency
import software.amazon.smithy.rust.codegen.core.rustlang.Local
import software.amazon.smithy.rust.codegen.core.rustlang.RustDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.asType
import software.amazon.smithy.rust.codegen.core.rustlang.rustInlineTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.util.orNull
import java.util.Optional

private const val DEFAULT_KEY = "DEFAULT"

/**
 * Location of the runtime crates (aws-smithy-http, aws-smithy-types etc.)
 *
 * This can be configured via the `runtimeConfig.versions` field in smithy-build.json
 */
data class RuntimeCrateLocation(val path: String?, val versions: CrateVersionMap) {
    companion object {
        fun Path(path: String) = RuntimeCrateLocation(path, CrateVersionMap(emptyMap()))
    }
}

fun RuntimeCrateLocation.crateLocation(crateName: String?): DependencyLocation {
    val version = crateName.let { versions.map[crateName] } ?: versions.map[DEFAULT_KEY]
    return when (this.path) {
        // CratesIo needs an exact version. However, for local runtime crates we do not
        // provide a detected version unless the user explicitly sets one via the `versions` map.
        null -> CratesIo(version ?: defaultRuntimeCrateVersion())
        else -> Local(this.path, version)
    }
}

fun defaultRuntimeCrateVersion(): String {
    try {
        return Version.crateVersion()
    } catch (ex: Exception) {
        throw CodegenException("failed to get crate version which sets the default client-runtime version", ex)
    }
}

/**
 * A mapping from crate name to a user-specified version.
 */
@JvmInline
value class CrateVersionMap(
    val map: Map<String, String>,
)

/**
 * Prefix & crate location for the runtime crates.
 */
data class RuntimeConfig(
    val cratePrefix: String = "aws-smithy",
    val runtimeCrateLocation: RuntimeCrateLocation = RuntimeCrateLocation.Path("../"),
) {
    companion object {

        /**
         * Load a `RuntimeConfig` from an [ObjectNode] (JSON)
         */
        fun fromNode(maybeNode: Optional<ObjectNode>): RuntimeConfig {
            val node = maybeNode.orElse(Node.objectNode())
            val crateVersionMap = node.getObjectMember("versions").orElse(Node.objectNode()).members.entries.let { members ->
                val map = members.associate { it.key.toString() to it.value.expectStringNode().value }
                CrateVersionMap(map)
            }
            val path = node.getStringMember("relativePath").orNull()?.value
            val runtimeCrateLocation = RuntimeCrateLocation(path = path, versions = crateVersionMap)
            return RuntimeConfig(
                node.getStringMemberOrDefault("cratePrefix", "aws-smithy"),
                runtimeCrateLocation = runtimeCrateLocation,
            )
        }
    }

    val crateSrcPrefix: String = cratePrefix.replace("-", "_")

    fun runtimeCrate(runtimeCrateName: String, optional: Boolean = false, scope: DependencyScope = DependencyScope.Compile): CargoDependency {
        val crateName = "$cratePrefix-$runtimeCrateName"
        return CargoDependency(
            crateName,
            runtimeCrateLocation.crateLocation(crateName),
            optional = optional,
            scope = scope,
        )
    }
}

/**
 * `RuntimeType` captures all necessary information to render a type into a Rust file:
 * - [name]: What type is this?
 * - [dependency]: What other crates, if any, are required to use this type?
 * - [namespace]: Where can we find this type.
 *
 * For example:
 *
 * `http::header::HeaderName`
 *  ------------  ----------
 *      |           |
 *  [namespace]   [name]
 *
 *  This type would have a [CargoDependency] pointing to the `http` crate.
 *
 *  By grouping all of this information, when we render a type into a [RustWriter], we can not only render a fully qualified
 *  name, but also ensure that we automatically add any dependencies **as they are used**.
 */
data class RuntimeType(val name: String?, val dependency: RustDependency?, val namespace: String) {
    /**
     * Get a writable for this `RuntimeType`
     */
    val writable = writable {
        rustInlineTemplate(
            "#{this:T}",
            "this" to this@RuntimeType,
        )
    }

    /**
     * Convert this [RuntimeType] into a [Symbol].
     *
     * This is not commonly required, but is occasionally useful when you want to force an import without referencing a type
     * (e.g. when bringing a trait into scope). See [CodegenWriter.addUseImports].
     */
    fun toSymbol(): Symbol {
        val builder = Symbol.builder().name(name).namespace(namespace, "::")
            .rustType(RustType.Opaque(name ?: "", namespace = namespace))

        dependency?.run { builder.addDependency(this) }
        return builder.build()
    }

    /**
     * Create a new [RuntimeType] with a nested name.
     *
     * # Example
     * ```kotlin
     * val http = CargoDependency.http.member("Request")
     * ```
     */
    fun member(member: String): RuntimeType {
        val newName = name?.let { "$name::$member" } ?: member
        return copy(name = newName)
    }

    /**
     * Returns the fully qualified name for this type
     */
    fun fullyQualifiedName(): String {
        val postFix = name?.let { "::$name" } ?: ""
        return "$namespace$postFix"
    }

    /**
     * The companion object contains commonly used RuntimeTypes
     */
    companion object {
        fun errorKind(runtimeConfig: RuntimeConfig) = RuntimeType(
            "ErrorKind",
            dependency = CargoDependency.SmithyTypes(runtimeConfig),
            namespace = "${runtimeConfig.crateSrcPrefix}_types::retry",
        )

        fun provideErrorKind(runtimeConfig: RuntimeConfig) = RuntimeType(
            "ProvideErrorKind",
            dependency = CargoDependency.SmithyTypes(runtimeConfig),
            namespace = "${runtimeConfig.crateSrcPrefix}_types::retry",
        )

        val std = RuntimeType(null, dependency = null, namespace = "std")
        val stdfmt = std.member("fmt")

        val AsRef = RuntimeType("AsRef", dependency = null, namespace = "std::convert")
        val ByteSlab = RuntimeType("Vec<u8>", dependency = null, namespace = "std::vec")
        val Clone = std.member("clone::Clone")
        val Debug = stdfmt.member("Debug")
        val Default: RuntimeType = RuntimeType("Default", dependency = null, namespace = "std::default")
        val Display = stdfmt.member("Display")
        val From = RuntimeType("From", dependency = null, namespace = "std::convert")
        val TryFrom = RuntimeType("TryFrom", dependency = null, namespace = "std::convert")
        val PartialEq = std.member("cmp::PartialEq")
        val StdError = RuntimeType("Error", dependency = null, namespace = "std::error")
        val String = RuntimeType("String", dependency = null, namespace = "std::string")

        fun DateTime(runtimeConfig: RuntimeConfig) =
            RuntimeType("DateTime", CargoDependency.SmithyTypes(runtimeConfig), "${runtimeConfig.crateSrcPrefix}_types")

        fun GenericError(runtimeConfig: RuntimeConfig) =
            RuntimeType("Error", CargoDependency.SmithyTypes(runtimeConfig), "${runtimeConfig.crateSrcPrefix}_types")

        fun Blob(runtimeConfig: RuntimeConfig) =
            RuntimeType("Blob", CargoDependency.SmithyTypes(runtimeConfig), "${runtimeConfig.crateSrcPrefix}_types")

        fun ByteStream(runtimeConfig: RuntimeConfig) =
            RuntimeType("ByteStream", CargoDependency.SmithyHttp(runtimeConfig), "${runtimeConfig.crateSrcPrefix}_http::byte_stream")

        fun Document(runtimeConfig: RuntimeConfig): RuntimeType =
            RuntimeType("Document", CargoDependency.SmithyTypes(runtimeConfig), "${runtimeConfig.crateSrcPrefix}_types")

        fun LabelFormat(runtimeConfig: RuntimeConfig, func: String) =
            RuntimeType(func, CargoDependency.SmithyHttp(runtimeConfig), "${runtimeConfig.crateSrcPrefix}_http::label")

        fun QueryFormat(runtimeConfig: RuntimeConfig, func: String) =
            RuntimeType(func, CargoDependency.SmithyHttp(runtimeConfig), "${runtimeConfig.crateSrcPrefix}_http::query")

        fun Base64Encode(runtimeConfig: RuntimeConfig): RuntimeType =
            RuntimeType(
                "encode",
                CargoDependency.SmithyTypes(runtimeConfig),
                "${runtimeConfig.crateSrcPrefix}_types::base64",
            )

        fun Base64Decode(runtimeConfig: RuntimeConfig): RuntimeType =
            RuntimeType(
                "decode",
                CargoDependency.SmithyTypes(runtimeConfig),
                "${runtimeConfig.crateSrcPrefix}_types::base64",
            )

        fun TimestampFormat(runtimeConfig: RuntimeConfig, format: TimestampFormatTrait.Format): RuntimeType {
            val timestampFormat = when (format) {
                TimestampFormatTrait.Format.EPOCH_SECONDS -> "EpochSeconds"
                TimestampFormatTrait.Format.DATE_TIME -> "DateTime"
                TimestampFormatTrait.Format.HTTP_DATE -> "HttpDate"
                TimestampFormatTrait.Format.UNKNOWN -> TODO()
            }
            return RuntimeType(
                timestampFormat,
                CargoDependency.SmithyTypes(runtimeConfig),
                "${runtimeConfig.crateSrcPrefix}_types::date_time::Format",
            )
        }

        fun ProtocolTestHelper(runtimeConfig: RuntimeConfig, func: String): RuntimeType =
            RuntimeType(
                func, CargoDependency.SmithyProtocolTestHelpers(runtimeConfig), "aws_smithy_protocol_test",
            )

        val http = CargoDependency.Http.asType()
        fun Http(path: String): RuntimeType =
            RuntimeType(name = path, dependency = CargoDependency.Http, namespace = "http")

        val HttpRequestBuilder = Http("request::Builder")
        val HttpResponseBuilder = Http("response::Builder")

        fun eventStreamReceiver(runtimeConfig: RuntimeConfig): RuntimeType =
            RuntimeType(
                "Receiver",
                dependency = CargoDependency.SmithyHttp(runtimeConfig),
                "aws_smithy_http::event_stream",
            )

        fun jsonErrors(runtimeConfig: RuntimeConfig) = forInlineDependency(InlineDependency.jsonErrors(runtimeConfig))

        val IdempotencyToken by lazy { forInlineDependency(InlineDependency.idempotencyToken()) }

        val Config = RuntimeType("config", null, "crate")

        fun operation(runtimeConfig: RuntimeConfig) = RuntimeType(
            "Operation",
            dependency = CargoDependency.SmithyHttp(runtimeConfig),
            namespace = "aws_smithy_http::operation",
        )

        fun operationModule(runtimeConfig: RuntimeConfig) = RuntimeType(
            null,
            dependency = CargoDependency.SmithyHttp(runtimeConfig),
            namespace = "aws_smithy_http::operation",
        )

        fun sdkBody(runtimeConfig: RuntimeConfig): RuntimeType =
            RuntimeType("SdkBody", dependency = CargoDependency.SmithyHttp(runtimeConfig), "aws_smithy_http::body")

        fun parseStrictResponse(runtimeConfig: RuntimeConfig) = RuntimeType(
            "ParseStrictResponse",
            dependency = CargoDependency.SmithyHttp(runtimeConfig),
            namespace = "aws_smithy_http::response",
        )

        val Bytes = RuntimeType("Bytes", dependency = CargoDependency.Bytes, namespace = "bytes")

        fun forInlineDependency(inlineDependency: InlineDependency) =
            RuntimeType(inlineDependency.name, inlineDependency, namespace = "crate")

        fun forInlineFun(name: String, module: RustModule, func: (RustWriter) -> Unit) = RuntimeType(
            name = name,
            dependency = InlineDependency(name, module, listOf(), func),
            namespace = "crate::${module.name}",
        )

        fun parseResponse(runtimeConfig: RuntimeConfig) = RuntimeType(
            "ParseHttpResponse",
            dependency = CargoDependency.SmithyHttp(runtimeConfig),
            namespace = "aws_smithy_http::response",
        )

        fun ec2QueryErrors(runtimeConfig: RuntimeConfig) =
            forInlineDependency(InlineDependency.ec2QueryErrors(runtimeConfig))

        fun wrappedXmlErrors(runtimeConfig: RuntimeConfig) =
            forInlineDependency(InlineDependency.wrappedXmlErrors(runtimeConfig))

        fun unwrappedXmlErrors(runtimeConfig: RuntimeConfig) =
            forInlineDependency(InlineDependency.unwrappedXmlErrors(runtimeConfig))
    }
}
