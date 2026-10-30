// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

/** Do not guess between namesakes, or split band names on commas / ampersands. */
internal object ArtistNavigation {
    fun name(credit:String)=credit.substringBefore(" · ").trim().replace(Regex("\\s+-\\s+Topic$",RegexOption.IGNORE_CASE),"").trim().take(200)
    fun exact(credit:String,candidates:List<MusicCard>):MusicCard? {
        val key=name(credit)
        return candidates.filter {it.kind=="artist" && name(it.title).equals(key,true)}.distinctBy {it.id}.singleOrNull()
    }
}
