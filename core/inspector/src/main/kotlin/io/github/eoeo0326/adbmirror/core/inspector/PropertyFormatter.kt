package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectorProperty
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.GetParametersResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.Parameter
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.GetPropertiesResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.Property
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.Resource as ComposeResource
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.Resource as ViewResource

/**
 * inspector 응답의 속성·파라미터를 [InspectorProperty]로 바꾼다. 이름과 문자열 값은 응답마다 오는 문자열 테이블의 id다.
 */
object PropertyFormatter {
    const val LAYOUT_GROUP = "Layout"
    const val PARAMETERS = "Parameters"
    const val MERGED_SEMANTICS = "Merged semantics"
    const val UNMERGED_SEMANTICS = "Unmerged semantics"

    /** View 속성. 레이아웃 속성(`is_layout`)은 [LAYOUT_GROUP]으로, 나머지는 namespace별로 묶는다. */
    fun view(response: GetPropertiesResponse): List<InspectorProperty> {
        val strings = response.stringsList.associate { it.id to it.str }
        return response.propertyGroup.propertyList.map { p ->
            val group = when {
                p.isLayout -> LAYOUT_GROUP
                else -> strings[p.namespace]?.takeIf { it.isNotEmpty() } ?: "attributes"
            }
            InspectorProperty(group, strings[p.name].orEmpty(), viewValue(p, strings))
        }.sortedWith(compareBy({ it.group != LAYOUT_GROUP }, { it.group }, { it.name }))
    }

    internal fun viewValue(p: Property, strings: Map<Int, String>): String = when (p.type) {
        Property.Type.STRING, Property.Type.INT_ENUM, Property.Type.OBJECT -> strings[p.int32Value] ?: if (p.int32Value == 0) "null" else ""
        Property.Type.BOOLEAN -> (p.int32Value != 0).toString()
        Property.Type.CHAR -> p.int32Value.toChar().toString()
        Property.Type.BYTE, Property.Type.INT16, Property.Type.INT32 -> p.int32Value.toString()
        Property.Type.INT64 -> p.int64Value.toString()
        Property.Type.DOUBLE -> p.doubleValue.toString()
        Property.Type.FLOAT -> p.floatValue.toString()
        Property.Type.COLOR -> color(p.int32Value)
        Property.Type.DIMENSION -> "${p.int32Value}px"
        Property.Type.GRAVITY, Property.Type.INT_FLAG ->
            p.flagValue.flagList.mapNotNull { strings[it] }.joinToString("|").ifEmpty { "none" }
        Property.Type.RESOURCE, Property.Type.DRAWABLE, Property.Type.ANIM, Property.Type.ANIMATOR, Property.Type.INTERPOLATOR ->
            if (p.hasResourceValue()) resource(p.resourceValue, strings) else "null"
        else -> ""
    }

    /** Compose 파라미터. Parameters / Merged semantics / Unmerged semantics 세 묶음으로 나눈다. */
    fun compose(response: GetParametersResponse): List<InspectorProperty> {
        val strings = response.stringsList.associate { it.id to it.str }
        val group = response.parameterGroup
        return group.parameterList.map { parameter(it, PARAMETERS, strings) } +
            group.mergedSemanticsList.map { parameter(it, MERGED_SEMANTICS, strings) } +
            group.unmergedSemanticsList.map { parameter(it, UNMERGED_SEMANTICS, strings) }
    }

    internal fun parameter(p: Parameter, group: String, strings: Map<Int, String>): InspectorProperty =
        InspectorProperty(
            group = group,
            name = strings[p.name].orEmpty(),
            value = parameterValue(p, strings),
            children = p.elementsList.map { parameter(it, group, strings) },
        )

    internal fun parameterValue(p: Parameter, strings: Map<Int, String>): String = when (p.type) {
        Parameter.Type.STRING -> strings[p.int32Value].orEmpty()
        Parameter.Type.BOOLEAN -> (p.int32Value != 0).toString()
        Parameter.Type.INT32 -> p.int32Value.toString()
        Parameter.Type.INT64 -> p.int64Value.toString()
        Parameter.Type.DOUBLE -> p.doubleValue.toString()
        Parameter.Type.FLOAT -> p.floatValue.toString()
        Parameter.Type.COLOR -> color(p.int32Value)
        Parameter.Type.DIMENSION_DP -> "${number(p.floatValue)}dp"
        Parameter.Type.DIMENSION_SP -> "${number(p.floatValue)}sp"
        Parameter.Type.DIMENSION_EM -> "${number(p.floatValue)}em"
        Parameter.Type.RESOURCE -> if (p.hasResourceValue()) resource(p.resourceValue, strings) else "null"
        Parameter.Type.LAMBDA, Parameter.Type.FUNCTION_REFERENCE -> {
            val l = p.lambdaValue
            val name = strings[l.functionName]?.takeIf { it.isNotEmpty() } ?: strings[l.lambdaName]?.takeIf { it.isNotEmpty() }
            val prefix = if (p.type == Parameter.Type.LAMBDA) "λ" else "::${name.orEmpty()}"
            val file = strings[l.fileName].orEmpty()
            if (file.isEmpty()) prefix else "$prefix $file:${l.startLineNumber}"
        }
        Parameter.Type.ITERABLE -> if (p.elementsCount == 0) "[]" else "[${p.elementsCount}]"
        else -> strings[p.int32Value].orEmpty()
    }

    fun color(argb: Int): String = "#%08X".format(argb)

    private fun number(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

    private fun resource(r: ViewResource, strings: Map<Int, String>): String = resource(strings[r.namespace], strings[r.type], strings[r.name])

    private fun resource(r: ComposeResource, strings: Map<Int, String>): String = resource(strings[r.namespace], strings[r.type], strings[r.name])

    private fun resource(namespace: String?, type: String?, name: String?): String {
        if (name.isNullOrEmpty()) return "null"
        val ns = namespace?.takeIf { it.isNotEmpty() && it != "app" }?.let { "$it:" }.orEmpty()
        return "@$ns${type.orEmpty()}/$name"
    }
}
