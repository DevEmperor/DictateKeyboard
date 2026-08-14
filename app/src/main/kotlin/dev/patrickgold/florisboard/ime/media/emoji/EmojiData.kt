/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.media.emoji

import android.content.Context
import dev.patrickgold.florisboard.lib.FlorisLocale
import org.florisboard.lib.android.bufferedReader
import io.github.reactivecircus.cache4k.Cache
import java.util.*

private typealias EmojiDataByCategoryImpl = EnumMap<EmojiCategory, MutableList<EmojiSet>>
private typealias EmojiDataBySkinToneImpl = EnumMap<EmojiSkinTone, MutableList<Emoji>>
typealias EmojiDataByCategory = Map<EmojiCategory, List<EmojiSet>>
typealias EmojiDataBySkinTone = Map<EmojiSkinTone, List<Emoji>>

data class EmojiData(
    val byCategory: EmojiDataByCategory,
    val bySkinTone: EmojiDataBySkinTone,
) {
    companion object {
        /** The emoji inventory: which emojis exist, in which order, with which skin-tone variants. */
        const val RootPath = "ime/media/emoji/root.txt"

        private val cache = Cache.Builder<String, EmojiData>().build()
        private val annotatedCache = Cache.Builder<String, EmojiData>().build()
        val Fallback = empty()

        private fun newByCategory(): EmojiDataByCategoryImpl {
            return EmojiDataByCategoryImpl(EmojiCategory::class.java).also { map ->
                for (category in EmojiCategory.entries) {
                    map[category] = mutableListOf()
                }
            }
        }

        private fun newBySkinTone(): EmojiDataBySkinToneImpl {
            return EmojiDataBySkinToneImpl(EmojiSkinTone::class.java).also { map ->
                for (skinTone in EmojiSkinTone.entries) {
                    map[skinTone] = mutableListOf()
                }
            }
        }

        fun empty(): EmojiData {
            return EmojiData(newByCategory(), newBySkinTone())
        }

        suspend fun get(context: Context, path: String): EmojiData {
            return cache.get(path) {
                loadEmojiDataMap(context, path)
            }
        }

        /**
         * The inventory with the names and keywords of [locale]'s language filled in, so it can be
         * searched and suggested against (issue #274). Emojis the language has no annotation for keep an
         * empty name — the search additionally consults the English index, so they are still findable.
         *
         * Before #274 this resolved a *per-language copy of the whole inventory*, of which only six were
         * ever shipped; every other language silently got an empty data set.
         */
        suspend fun annotated(context: Context, locale: FlorisLocale): EmojiData {
            val language = EmojiAnnotations.assetLanguage(locale)
            return annotatedCache.get(language) {
                val root = get(context, RootPath)
                val annotations = EmojiAnnotations.get(context, language)
                if (annotations.isEmpty()) root else root.withAnnotations(annotations)
            }
        }

        private fun loadEmojiDataMap(context: Context, path: String): EmojiData {
            return context.assets.bufferedReader(path).useLines { parse(it) }
        }

        /**
         * Parses the inventory format: `[category]` headers, one base emoji per line as
         * `value;name;keyword|keyword`, its skin-tone variants indented with a tab beneath it.
         *
         * Split out from the asset loading so the emoji search can be tested against the real shipped
         * files on the JVM, without a device (see `EmojiSearchEngineTest`).
         */
        fun parse(lines: Sequence<String>): EmojiData {
            val byCategory = newByCategory()
            val bySkinTone = newBySkinTone()

            var ec: EmojiCategory? = null
            var emojiEditorList: MutableList<Emoji>? = null

            fun commitEmojiEditorList() {
                emojiEditorList?.let { byCategory[ec]!!.add(EmojiSet(it)) }
                emojiEditorList = null
            }

            for (line in lines) {
                if (line.startsWith("#")) {
                    // Comment line
                } else if (line.startsWith("[")) {
                    commitEmojiEditorList()
                    ec = EmojiCategory.entries.find { it.id == line.slice(1 until (line.length - 1)) }
                } else if (line.trim().isEmpty() || ec == null) {
                    // Empty line
                    continue
                } else {
                    if (!line.startsWith("\t")) {
                        commitEmojiEditorList()
                    }
                    // Assume it is a data line
                    val data = line.split(";")
                    if (data.size == 3) {
                        val base = emojiEditorList?.first()
                        val emoji = Emoji(
                            value = data[0].trim(),
                            name = base?.name ?: data[1].trim(),
                            keywords = data[2].split("|").map { it.trim() },
                        )
                        if (emojiEditorList != null) {
                            emojiEditorList!!.add(emoji)
                        } else {
                            emojiEditorList = mutableListOf(emoji)
                        }
                    }
                }
            }
            commitEmojiEditorList()

            for (category in byCategory.keys) {
                for (emojiSet in byCategory[category]!!) {
                    if (emojiSet.emojis.size == 1) {
                        // No variations provided, we fallback to using the base for all skin tones
                        val base = emojiSet.emojis.first()
                        for (skinTone in EmojiSkinTone.entries) {
                            bySkinTone[skinTone]!!.add(base)
                        }
                        continue
                    }
                    for (emoji in emojiSet.emojis) {
                        bySkinTone[emoji.skinTone]!!.add(emoji)
                    }
                }
            }

            return EmojiData(byCategory, bySkinTone)
        }

    }

    /**
     * A copy of this inventory whose emojis carry the name and keywords from [annotations].
     *
     * Skin-tone variants inherit the base emoji's text, which is how the old fused files worked too: a
     * variant's own name only ever repeated the base's with the tone appended.
     */
    fun withAnnotations(annotations: Map<String, EmojiAnnotation>): EmojiData {
        val byCategory = EmojiDataByCategoryImpl(EmojiCategory::class.java)
        val bySkinTone = EmojiDataBySkinToneImpl(EmojiSkinTone::class.java)
        for (skinTone in EmojiSkinTone.entries) {
            bySkinTone[skinTone] = mutableListOf()
        }
        for ((category, sets) in this.byCategory) {
            val annotatedSets = ArrayList<EmojiSet>(sets.size)
            for (set in sets) {
                val annotation = annotations[set.emojis.first().value]
                val annotated = if (annotation == null) {
                    set
                } else {
                    EmojiSet(set.emojis.map { Emoji(it.value, annotation.name, annotation.keywords) })
                }
                annotatedSets.add(annotated)
                if (annotated.emojis.size == 1) {
                    val base = annotated.emojis.first()
                    for (skinTone in EmojiSkinTone.entries) {
                        bySkinTone[skinTone]!!.add(base)
                    }
                } else {
                    for (emoji in annotated.emojis) {
                        bySkinTone[emoji.skinTone]!!.add(emoji)
                    }
                }
            }
            byCategory[category] = annotatedSets
        }
        return EmojiData(byCategory, bySkinTone)
    }
}
