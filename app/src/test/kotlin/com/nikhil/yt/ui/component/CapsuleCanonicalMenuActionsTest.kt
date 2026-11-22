package com.nikhil.yt.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stable More action placement regardless of provider-specific omissions. */
class CapsuleCanonicalMenuActionsTest {
    private fun action(text: String) = NewAction(icon = {}, text = text, onClick = {})

    @Test fun linkAlwaysOccupiesRightPrioritySlotAndPlaylistLeft() {
        val actions = listOf(
            action("Радио"), action("Скопировать ссылку"),
            action("Эквалайзер"), action("Добавить в плейлист"), action("Скачать"),
        )
        val result = capsuleCanonicalMenuActions(
            actions, "Добавить в плейлист", "Скопировать ссылку", "Поделиться",
        )
        assertEquals(
            listOf("Добавить в плейлист", "Скопировать ссылку",
                "Радио", "Эквалайзер", "Скачать"),
            result.map { it.text },
        )
    }

    @Test fun copiesWithoutPlaylistUseFirstAvailableActionOnLeft() {
        val actions = listOf(action("Воспроизвести"), action("Перемешать"),
            action("Скопировать ссылку"))
        val result = capsuleCanonicalMenuActions(actions, "Добавить в плейлист",
            "Скопировать ссылку", "Поделиться")
        assertEquals(listOf("Воспроизвести", "Скопировать ссылку", "Перемешать"),
            result.map { it.text })
    }

    @Test fun localFilesKeepNativeShareOnRightAndPreserveActions() {
        val actions = listOf(action("Радио"), action("Поделиться"),
            action("Добавить в плейлист"))
        val result = capsuleCanonicalMenuActions(actions, "Добавить в плейлист",
            "Скопировать ссылку", "Поделиться")
        assertEquals(listOf("Добавить в плейлист", "Поделиться", "Радио"),
            result.map { it.text })
    }

    @Test fun missingLinkDoesNotMoveOrDiscardActions() {
        val actions = listOf(action("Радио"), action("В очередь"))
        assertEquals(actions, capsuleCanonicalMenuActions(actions,
            "Добавить в плейлист", "Скопировать ссылку", "Поделиться"))
        assertTrue(capsuleCanonicalMenuActions(emptyList(),
            "Добавить в плейлист", "Скопировать ссылку", "Поделиться").isEmpty())
    }
}
