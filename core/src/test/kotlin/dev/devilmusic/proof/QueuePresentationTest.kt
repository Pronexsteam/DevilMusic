package dev.devilmusic.proof

import kotlin.test.*

class QueuePresentationTest {
    private val a = QueuePresentation.Item("content://files/A", "01-a.mp3")
    private val b = QueuePresentation.Item("content://files/B", "02-b.mp3")

    @Test fun selectingAThenBThenADoesNotReorderOrRevertLearnedTitles() {
        val model = QueuePresentation(); val source = listOf(a, b); model.update(source)
        model.remember(a.id, "A embedded title", "Artist A")
        for (id in listOf(a.id, b.id, a.id)) {
            if (id == b.id) model.remember(id, "B embedded title", "Artist B")
            val index = queuePositionForId(id, source.map { it.id })!!
            val rows = model.rows(index)
            assertEquals(listOf(a.id, b.id), rows.map { it.id })
            assertEquals(listOf(1, 2), rows.map { it.number })
            assertEquals(id, rows.single { it.selected }.id)
            assertEquals("A embedded title", rows[0].title)
            assertTrue(rows[0].subtitle.contains(a.fileName))
        }
        assertEquals("B embedded title", model.rows(0)[1].title)
        assertEquals(listOf(a, b), source)
    }

    @Test fun nextPreviousAndMetadataRefreshPreservePositions() {
        val model = QueuePresentation(); model.update(listOf(a, b))
        model.remember(a.id, "A", null); model.remember(b.id, "B", null)
        for (position in listOf(0, 1, 0, 1)) {
            model.update(listOf(a, b)) // A service metadata event must not wipe enriched text.
            val rows = model.rows(position)
            assertEquals(listOf("A", "B"), rows.map { it.title })
            assertEquals(position + 1, rows.single { it.selected }.number)
        }
    }

    @Test fun identicalTitlesUseIdsAndKeepFileNamesVisible() {
        val model = QueuePresentation(); model.update(listOf(a, b))
        model.remember(a.id, "Same title", "Same artist"); model.remember(b.id, "Same title", "Same artist")
        assertEquals(1, queuePositionForId(b.id, listOf(a.id, b.id)))
        val rows = model.rows(1)
        assertEquals(2, rows.size); assertTrue(rows[1].selected); assertFalse(rows[0].selected)
        assertTrue(rows[0].subtitle.contains(a.fileName)); assertTrue(rows[1].subtitle.contains(b.fileName))
    }

    @Test fun removedItemAndLateMetadataCannotSelectOrRenameAnotherTrack() {
        val model = QueuePresentation(); model.update(listOf(a, b)); model.remember(a.id, "Cached A", null)
        model.update(listOf(b)); model.remember(a.id, "Late A", null)
        assertNull(queuePositionForId(a.id, listOf(b.id)))
        assertEquals(b.fileName, model.rows(0).single().title)
        model.update(listOf(a, b)); assertEquals(a.fileName, model.rows(0)[0].title)
        assertEquals(0, queuePositionForId(b.id, listOf(b.id, a.id)))
    }
}
