package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectorProperty
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.Parameter
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.Property
import kotlin.test.Test
import kotlin.test.assertEquals

class PropertyFormatterTest {
    private val strings = listOf("android", "text", "Hello", "layout_width", "gravity", "center", "top", "background", "drawable", "bg", "visibility", "VISIBLE", "textColor", "textSize")
    private fun s(v: String) = strings.indexOf(v) + 1

    private fun viewResponse(vararg props: Property) = ViewLayoutInspection.GetPropertiesResponse.newBuilder()
        .addAllStrings(strings.mapIndexed { i, v -> ViewLayoutInspection.StringEntry.newBuilder().setId(i + 1).setStr(v).build() })
        .setPropertyGroup(ViewLayoutInspection.PropertyGroup.newBuilder().addAllProperty(props.toList()))
        .build()

    private fun prop(name: String, type: Property.Type, layout: Boolean = false, fill: Property.Builder.() -> Unit) =
        Property.newBuilder().setName(s(name)).setNamespace(s("android")).setType(type).setIsLayout(layout).apply(fill).build()

    @Test
    fun formatsViewPropertiesAndGroupsLayoutFirst() {
        val result = PropertyFormatter.view(
            viewResponse(
                prop("text", Property.Type.STRING) { int32Value = s("Hello") },
                prop("layout_width", Property.Type.DIMENSION, layout = true) { int32Value = 300 },
                prop("gravity", Property.Type.GRAVITY) { flagValue = ViewLayoutInspection.FlagValue.newBuilder().addFlag(s("center")).addFlag(s("top")).build() },
                prop("background", Property.Type.DRAWABLE) {
                    resourceValue = ViewLayoutInspection.Resource.newBuilder().setNamespace(s("android")).setType(s("drawable")).setName(s("bg")).build()
                },
                prop("visibility", Property.Type.INT_ENUM) { int32Value = s("VISIBLE") },
                prop("textColor", Property.Type.COLOR) { int32Value = 0xFF336699.toInt() },
            ),
        )
        assertEquals(
            listOf(
                InspectorProperty("Layout", "layout_width", "300px"),
                InspectorProperty("android", "background", "@android:drawable/bg"),
                InspectorProperty("android", "gravity", "center|top"),
                InspectorProperty("android", "text", "Hello"),
                InspectorProperty("android", "textColor", "#FF336699"),
                InspectorProperty("android", "visibility", "VISIBLE"),
            ),
            result,
        )
    }

    private val composeStrings = listOf("text", "Hello", "modifier", "padding", "onClick", "Greeting.kt", "fontSize", "items", "Role", "Button")
    private fun c(v: String) = composeStrings.indexOf(v) + 1

    @Test
    fun formatsComposeParametersInThreeGroups() {
        fun param(name: String, type: Parameter.Type, fill: Parameter.Builder.() -> Unit = {}) =
            Parameter.newBuilder().setName(c(name)).setType(type).apply(fill).build()
        val response = ComposeLayoutInspection.GetParametersResponse.newBuilder()
            .addAllStrings(composeStrings.mapIndexed { i, v -> ComposeLayoutInspection.StringEntry.newBuilder().setId(i + 1).setStr(v).build() })
            .setParameterGroup(
                ComposeLayoutInspection.ParameterGroup.newBuilder()
                    .addParameter(param("text", Parameter.Type.STRING) { int32Value = c("Hello") })
                    .addParameter(param("fontSize", Parameter.Type.DIMENSION_SP) { floatValue = 16f })
                    .addParameter(param("padding", Parameter.Type.DIMENSION_DP) { floatValue = 1.5f })
                    .addParameter(
                        param("onClick", Parameter.Type.LAMBDA) {
                            lambdaValue = ComposeLayoutInspection.LambdaValue.newBuilder().setFileName(c("Greeting.kt")).setStartLineNumber(42).build()
                        },
                    )
                    .addParameter(
                        param("items", Parameter.Type.ITERABLE) {
                            addElements(param("text", Parameter.Type.INT32) { int32Value = 7 })
                        },
                    )
                    .addMergedSemantics(param("Role", Parameter.Type.STRING) { int32Value = c("Button") }),
            ).build()
        val result = PropertyFormatter.compose(response)
        assertEquals(
            listOf(
                InspectorProperty("Parameters", "text", "Hello"),
                InspectorProperty("Parameters", "fontSize", "16sp"),
                InspectorProperty("Parameters", "padding", "1.5dp"),
                InspectorProperty("Parameters", "onClick", "λ Greeting.kt:42"),
                InspectorProperty("Parameters", "items", "[1]", listOf(InspectorProperty("Parameters", "text", "7"))),
                InspectorProperty("Merged semantics", "Role", "Button"),
            ),
            result,
        )
    }
}
