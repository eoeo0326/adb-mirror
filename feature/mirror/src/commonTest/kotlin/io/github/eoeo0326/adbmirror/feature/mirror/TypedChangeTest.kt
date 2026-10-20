package io.github.eoeo0326.adbmirror.feature.mirror

import kotlin.test.Test
import kotlin.test.assertEquals

class TypedChangeTest {
    @Test
    fun appendedTextIsSentOnce() {
        assertEquals(TypedChange(0, "스"), TypedChange.between("테", "테스"))
    }

    @Test
    fun recommittingSameCharacterSendsNothing() {
        // 입력기가 "스"를 지우고 다시 확정해도 확정된 글은 그대로라 다시 보내지 않는다(테스스트가 되지 않음).
        assertEquals(TypedChange(0, ""), TypedChange.between("테스", "테스"))
    }

    @Test
    fun replacedCharacterIsDeletedThenSent() {
        assertEquals(TypedChange(1, "트"), TypedChange.between("테스", "테트"))
    }

    @Test
    fun fromEmpty() {
        assertEquals(TypedChange(0, "hello"), TypedChange.between("", "hello"))
    }
}
