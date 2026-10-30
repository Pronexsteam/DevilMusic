package dev.devilmusic.proof

/** A projection of service order, never an owner or sorter of playback order. Text cache only. */
class QueuePresentation {
    data class Item(val id: String, val fileName: String, val title: String? = null, val artist: String? = null)
    data class Row(val id: String, val number: Int, val title: String, val subtitle: String, val selected: Boolean)
    private data class Tags(val title: String?, val artist: String?)
    private var items = emptyList<Item>()
    private val tags = mutableMapOf<String, Tags>()

    fun update(snapshot: List<Item>) {
        items = snapshot.toList()
        tags.keys.retainAll(items.map { it.id }.toSet())
    }

    fun remember(id: String, title: String?, artist: String?) {
        if (items.none { it.id == id }) return
        fun clean(value: String?) = value?.trim()?.take(256)?.takeIf { it.isNotEmpty() }
        val previous = tags[id]
        tags[id] = Tags(clean(title) ?: previous?.title, clean(artist) ?: previous?.artist)
    }

    fun rows(currentIndex: Int): List<Row> = items.mapIndexed { index, item ->
        val metadata = tags[item.id]
        val title = metadata?.title ?: item.title?.takeIf { it.isNotBlank() } ?: item.fileName
        val artist = metadata?.artist ?: item.artist?.takeIf { it.isNotBlank() }
        // The original file name stays visible when tags change the title, including duplicate titles.
        val detail = listOfNotNull(artist, item.fileName.takeIf { it != title }).joinToString(" · ")
        Row(item.id, index + 1, title, detail, index == currentIndex)
    }
}

/** Resolve against the live service order at tap time, not a title or a stale display position. */
fun queuePositionForId(id: String, serviceIds: List<String>): Int? = serviceIds.indexOf(id).takeIf { it >= 0 }
