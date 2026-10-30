// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor

/** LRCLIB Lyricsfile 1.0: use authored milliseconds, never spread a line into invented word timings. */
internal object Lyricsfile {
    fun parse(source:String):Lyrics {
        require(source.length<=512000)
        val options=LoaderOptions().apply {codePointLimit=512000;nestingDepthLimit=12;maxAliasesForCollections=0;isAllowDuplicateKeys=false}
        val root=Yaml(SafeConstructor(options)).load<Any>(source) as? Map<*,*> ?: error("Missing lyrics document")
        require(root["version"].toString()=="1.0")
        fun time(value:Any?):Long? {val n=value as? Number ?: return null;return n.toLong().takeIf {it in 0..86_400_000 && n.toDouble()==it.toDouble()}}
        val values=root["lines"] as? List<*> ?: emptyList<Any>();require(values.size<=3000)
        val lines=values.mapNotNull {v->
            val line=v as? Map<*,*> ?: return@mapNotNull null
            val start=time(line["start_ms"]) ?: return@mapNotNull null
            val end=time(line["end_ms"]);require(end==null || end>=start)
            val text=(line["text"] as? String).orEmpty();require(text.length<=8192)
            val marks=line["words"] as? List<*> ?: emptyList<Any>();require(marks.size<=2000)
            val words=marks.map {w->
                val m=w as? Map<*,*> ?: error("Invalid word")
                val from=time(m["start_ms"]) ?: error("Missing word start");val to=time(m["end_ms"]) ?: error("Missing word end")
                require(from>=start && to>=from && (end==null || to<=end))
                LyricWord(from,(m["text"] as? String) ?: error("Missing word text"),to)
            }
            require(words.zipWithNext().all {it.first.time<=it.second.time})
            LyricLine(start,text.ifBlank {words.joinToString(""){it.text}},words,end)
        }.sortedBy {it.time}
        val metadata=root["metadata"] as? Map<*,*>
        return Lyrics(lines,(root["plain"] as? String).orEmpty(),metadata?.get("instrumental")==true,"LRCLIB · Lyricsfile")
    }
}
