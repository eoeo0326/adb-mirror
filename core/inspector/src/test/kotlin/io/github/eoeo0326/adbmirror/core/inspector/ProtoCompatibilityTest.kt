package io.github.eoeo0326.adbmirror.core.inspector

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 우리 `.proto`가 Android Studio에 들어 있는 컴파일된 proto와 와이어 호환인지 확인한다.
 * `./gradlew :core:inspector:test -Padbmirror.studio=<Android Studio 설치 경로>`일 때만 돈다.
 *
 * Studio의 `plugins/android/lib/` jar를 별도 클래스로더로 올려 모든 proto 파일 descriptor를 모은 뒤,
 * 우리 메시지마다 같은 전체 이름의 메시지에서 같은 번호 필드의 이름·와이어 타입을 비교한다.
 * Studio는 protobuf를 shading해 두었으므로 리플렉션으로 `toProto().toByteArray()`만 꺼낸다.
 */
class ProtoCompatibilityTest {
    @Test
    fun matchesAndroidStudioProtos() {
        val studio = System.getProperty("adbmirror.studio")?.takeIf { it.isNotBlank() } ?: return
        val theirs = studioMessages(File(studio))
        assertTrue(theirs.isNotEmpty(), "Studio proto를 하나도 읽지 못했습니다: $studio")

        val problems = mutableListOf<String>()
        listOf(
            Transport.getDescriptor(),
            AppInspection.getDescriptor(),
            ViewLayoutInspection.getDescriptor(),
            ComposeLayoutInspection.getDescriptor(),
        ).forEach { file -> compareFile(file.toProto(), theirs, problems) }
        if (problems.isNotEmpty()) fail("Studio proto와 다른 곳 ${problems.size}개:\n" + problems.joinToString("\n"))
    }

    private class Theirs(val messages: Map<String, DescriptorProto>, val enums: Map<String, EnumDescriptorProto>) {
        fun isEmpty() = messages.isEmpty()
        fun isNotEmpty() = !isEmpty()
    }

    private fun studioMessages(studio: File): Theirs {
        val contents = if (File(studio, "Contents").isDirectory) File(studio, "Contents") else studio
        val jars = File(contents, "plugins/android/lib").listFiles { f -> f.extension == "jar" }.orEmpty()
        val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
        val messages = HashMap<String, DescriptorProto>()
        val enums = HashMap<String, EnumDescriptorProto>()
        val seenFiles = HashSet<String>()
        jars.filter { it.name.contains("proto") }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") && '$' !in it && !it.endsWith("Grpc.class") }
                    .forEach { entry ->
                        val bytes = descriptorBytes(loader, entry.removeSuffix(".class").replace('/', '.')) ?: return@forEach
                        val proto = FileDescriptorProto.parseFrom(bytes)
                        if (seenFiles.add(proto.name)) collect(proto.`package`, proto.messageTypeList, proto.enumTypeList, messages, enums)
                    }
            }
        }
        return Theirs(messages, enums)
    }

    /** 외곽 클래스의 static `getDescriptor()`(FileDescriptor)를 찾아 직렬화한다. proto 외곽 클래스가 아니면 null. */
    private fun descriptorBytes(loader: ClassLoader, className: String): ByteArray? = runCatching {
        val cls = Class.forName(className, false, loader)
        val method = cls.getMethod("getDescriptor")
        if (!java.lang.reflect.Modifier.isStatic(method.modifiers) || !method.returnType.name.endsWith("Descriptors\$FileDescriptor")) return null
        val file = method.invoke(null)
        val proto = file.javaClass.getMethod("toProto").invoke(file)
        proto.javaClass.getMethod("toByteArray").invoke(proto) as ByteArray
    }.getOrNull()

    private fun collect(
        prefix: String,
        types: List<DescriptorProto>,
        topEnums: List<EnumDescriptorProto>,
        messages: MutableMap<String, DescriptorProto>,
        enums: MutableMap<String, EnumDescriptorProto>,
    ) {
        topEnums.forEach { enums["$prefix.${it.name}"] = it }
        types.forEach { type ->
            val name = "$prefix.${type.name}"
            messages[name] = type
            collect(name, type.nestedTypeList, type.enumTypeList, messages, enums)
        }
    }

    private fun compareFile(ours: FileDescriptorProto, theirs: Theirs, problems: MutableList<String>) {
        val ourMessages = HashMap<String, DescriptorProto>()
        val ourEnums = HashMap<String, EnumDescriptorProto>()
        collect(ours.`package`, ours.messageTypeList, ours.enumTypeList, ourMessages, ourEnums)

        ourMessages.forEach { (name, message) ->
            val other = theirs.messages[name] ?: run { problems.add("$name: Studio에 없는 메시지"); return@forEach }
            message.fieldList.forEach { field ->
                val match = other.fieldList.firstOrNull { it.number == field.number }
                when {
                    match == null -> problems.add("$name.${field.name}(${field.number}): Studio에 없는 번호")
                    match.name != field.name -> problems.add("$name(${field.number}): 이름 ${field.name} ≠ Studio ${match.name}")
                    !wireCompatible(field, match) -> problems.add("$name.${field.name}: 타입 ${field.type} ≠ Studio ${match.type}")
                }
            }
        }
        ourEnums.forEach { (name, enum) ->
            val other = theirs.enums[name] ?: run { problems.add("$name: Studio에 없는 enum"); return@forEach }
            enum.valueList.forEach { value ->
                val match = other.valueList.firstOrNull { it.number == value.number }
                if (match?.name != value.name) problems.add("$name.${value.name}(${value.number}): Studio ${match?.name}")
            }
        }
    }

    /** 이름이 같은 필드의 와이어 형식이 같은지. 일부러 바꾼 enum→int32, 메시지→bytes는 허용한다. */
    private fun wireCompatible(ours: FieldDescriptorProto, theirs: FieldDescriptorProto): Boolean {
        if (ours.label != theirs.label) return false
        if (ours.type == theirs.type) return true
        val t = FieldDescriptorProto.Type.TYPE_INT32 to FieldDescriptorProto.Type.TYPE_ENUM
        val m = FieldDescriptorProto.Type.TYPE_BYTES to FieldDescriptorProto.Type.TYPE_MESSAGE
        return (ours.type to theirs.type) == t || (ours.type to theirs.type) == m
    }
}
