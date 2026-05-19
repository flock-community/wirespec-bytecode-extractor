// src/main/kotlin/community/flock/wirespec/spring/extractor/ast/WirespecAstBuilder.kt
package community.flock.wirespec.spring.extractor.ast

import community.flock.wirespec.compiler.core.parse.ast.Comment
import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint as WsEndpoint
import community.flock.wirespec.compiler.core.parse.ast.Enum as WsEnum
import community.flock.wirespec.compiler.core.parse.ast.Field as WsField
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Refined as WsRefined
import community.flock.wirespec.compiler.core.parse.ast.Type as WsType
import community.flock.wirespec.spring.extractor.model.Endpoint
import community.flock.wirespec.spring.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.spring.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.spring.extractor.model.Param
import community.flock.wirespec.spring.extractor.model.WireType

class WirespecAstBuilder {

    fun toEndpoint(ep: Endpoint): WsEndpoint = WsEndpoint(
        comment = null,
        annotations = emptyList(),
        identifier = DefinitionIdentifier(ep.name),
        method = ep.method.toWs(),
        path = ep.pathSegments.map { it.toWs() },
        queries = ep.queryParams.map { it.toField() },
        headers = ep.headerParams.map { it.toField() },
        requests = listOf(
            WsEndpoint.Request(
                content = ep.requestBody?.let { WsEndpoint.Content("application/json", toReference(it)) }
            )
        ),
        responses = ep.responses.map { r ->
            WsEndpoint.Response(
                status = r.statusCode.toString(),
                headers = emptyList(),
                content = r.body?.let { WsEndpoint.Content("application/json", toReference(it)) },
                annotations = emptyList(),
            )
        },
    )

    fun toDefinition(wt: WireType): Definition = when (wt) {
        is WireType.Object -> WsType(
            comment = wt.description?.let { Comment(it) },
            annotations = emptyList(),
            identifier = DefinitionIdentifier(wt.name),
            shape = WsType.Shape(
                value = wt.fields.map { f ->
                    WsField(
                        annotations = emptyList(),
                        identifier = FieldIdentifier(f.name),
                        reference = toReference(f.type),
                    )
                }
            ),
            extends = emptyList(),
        )
        is WireType.EnumDef -> WsEnum(
            comment = wt.description?.let { Comment(it) },
            annotations = emptyList(),
            identifier = DefinitionIdentifier(wt.name),
            entries = wt.values.toSet(),
        )
        is WireType.Refined -> WsRefined(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier(wt.name),
            reference = buildRefinedReference(wt),
        )
        else -> throw IllegalArgumentException("Not a top-level definition: $wt")
    }

    fun toReference(wt: WireType): Reference = when (wt) {
        is WireType.Primitive -> primitiveRef(wt)
        is WireType.Ref       -> Reference.Custom(wt.name, wt.nullable)
        is WireType.ListOf    -> Reference.Iterable(toReference(wt.element), wt.nullable)
        is WireType.MapOf     -> Reference.Dict(toReference(wt.value), wt.nullable)
        is WireType.Object    -> Reference.Custom(wt.name, wt.nullable)
        is WireType.EnumDef   -> Reference.Custom(wt.name, wt.nullable)
        is WireType.Refined   -> Reference.Custom(wt.name, wt.nullable)
    }

    private fun buildRefinedReference(r: WireType.Refined): Reference.Primitive {
        val type: Reference.Primitive.Type = when (r.base.kind) {
            WireType.Primitive.Kind.STRING -> Reference.Primitive.Type.String(
                constraint = r.regex?.let { Reference.Primitive.Type.Constraint.RegExp(it) }
            )
            WireType.Primitive.Kind.INTEGER_32 -> Reference.Primitive.Type.Integer(
                precision = Reference.Primitive.Type.Precision.P32,
                constraint = buildBoundConstraint(r),
            )
            WireType.Primitive.Kind.INTEGER_64 -> Reference.Primitive.Type.Integer(
                precision = Reference.Primitive.Type.Precision.P64,
                constraint = buildBoundConstraint(r),
            )
            WireType.Primitive.Kind.NUMBER_32 -> Reference.Primitive.Type.Number(
                precision = Reference.Primitive.Type.Precision.P32,
                constraint = buildBoundConstraint(r),
            )
            WireType.Primitive.Kind.NUMBER_64 -> Reference.Primitive.Type.Number(
                precision = Reference.Primitive.Type.Precision.P64,
                constraint = buildBoundConstraint(r),
            )
            WireType.Primitive.Kind.BOOLEAN -> Reference.Primitive.Type.Boolean
            WireType.Primitive.Kind.BYTES   -> Reference.Primitive.Type.Bytes
        }
        return Reference.Primitive(type, r.nullable)
    }

    private fun buildBoundConstraint(r: WireType.Refined): Reference.Primitive.Type.Constraint.Bound? =
        if (r.min != null || r.max != null) Reference.Primitive.Type.Constraint.Bound(r.min, r.max) else null

    private fun primitiveRef(p: WireType.Primitive): Reference.Primitive {
        val type: Reference.Primitive.Type = when (p.kind) {
            WireType.Primitive.Kind.STRING     -> Reference.Primitive.Type.String(constraint = null)
            WireType.Primitive.Kind.INTEGER_32 -> Reference.Primitive.Type.Integer(
                precision = Reference.Primitive.Type.Precision.P32,
                constraint = null,
            )
            WireType.Primitive.Kind.INTEGER_64 -> Reference.Primitive.Type.Integer(
                precision = Reference.Primitive.Type.Precision.P64,
                constraint = null,
            )
            WireType.Primitive.Kind.NUMBER_32  -> Reference.Primitive.Type.Number(
                precision = Reference.Primitive.Type.Precision.P32,
                constraint = null,
            )
            WireType.Primitive.Kind.NUMBER_64  -> Reference.Primitive.Type.Number(
                precision = Reference.Primitive.Type.Precision.P64,
                constraint = null,
            )
            WireType.Primitive.Kind.BOOLEAN    -> Reference.Primitive.Type.Boolean
            WireType.Primitive.Kind.BYTES      -> Reference.Primitive.Type.Bytes
        }
        return Reference.Primitive(type, p.nullable)
    }

    private fun PathSegment.toWs(): WsEndpoint.Segment = when (this) {
        is PathSegment.Literal  -> WsEndpoint.Segment.Literal(value)
        is PathSegment.Variable -> WsEndpoint.Segment.Param(FieldIdentifier(name), toReference(type))
    }

    private fun Param.toField(): WsField = WsField(
        annotations = emptyList(),
        identifier = FieldIdentifier(name),
        reference = toReference(type),
    )

    private fun HttpMethod.toWs(): WsEndpoint.Method = when (this) {
        HttpMethod.GET     -> WsEndpoint.Method.GET
        HttpMethod.POST    -> WsEndpoint.Method.POST
        HttpMethod.PUT     -> WsEndpoint.Method.PUT
        HttpMethod.PATCH   -> WsEndpoint.Method.PATCH
        HttpMethod.DELETE  -> WsEndpoint.Method.DELETE
        HttpMethod.OPTIONS -> WsEndpoint.Method.OPTIONS
        HttpMethod.HEAD    -> WsEndpoint.Method.HEAD
        HttpMethod.TRACE   -> WsEndpoint.Method.TRACE
    }
}
