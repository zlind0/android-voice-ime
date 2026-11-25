package com.litertime.android.ime.traditional

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Loads and caches FlorisBoard's Unicode CLDR emoji categories from `assets/emoji/root.txt`.
 */
object EmojiCatalog {
    data class Category(
        val id: String,
        val title: String,
        val icon: String,
        val emojis: List<String>,
    )

    private data class CategoryMeta(
        val title: String,
        val icon: String,
    )

    private val categoryMetaMap = mapOf(
        "smileys_emotion" to CategoryMeta("表情", "😀"),
        "people_body" to CategoryMeta("人物", "👋"),
        "animals_nature" to CategoryMeta("动物", "🐶"),
        "food_drink" to CategoryMeta("食物", "🍔"),
        "travel_places" to CategoryMeta("旅行", "✈️"),
        "activities" to CategoryMeta("活动", "⚽"),
        "objects" to CategoryMeta("物品", "💡"),
        "symbols" to CategoryMeta("符号", "❤️"),
        "flags" to CategoryMeta("旗帜", "🏁"),
    )

    @Volatile
    private var cachedCategories: List<Category>? = null

    fun load(context: Context): List<Category> {
        cachedCategories?.let { return it }
        synchronized(this) {
            cachedCategories?.let { return it }
            val text = runCatching {
                context.assets.open("emoji/root.txt").use { stream ->
                    String(stream.readBytes(), StandardCharsets.UTF_8)
                }
            }.getOrDefault("")
            val parsed = parseRootTxt(text)
            cachedCategories = parsed
            return parsed
        }
    }

    /**
     * Parses FlorisBoard `emoji/root.txt`:
     * - `[section_id]` starts a category
     * - Lines starting with `#` or whitespace are comments / skin-tone variants and are skipped
     * - Base emoji lines have the format `<emoji>;<name>;<keywords>`
     */
    fun parseRootTxt(content: String): List<Category> {
        if (content.isBlank()) return emptyList()
        val result = mutableListOf<Category>()
        var currentId: String? = null
        var currentEmojis = mutableListOf<String>()

        fun flushCategory() {
            val id = currentId ?: return
            if (currentEmojis.isNotEmpty()) {
                val meta = categoryMetaMap[id] ?: CategoryMeta(id, currentEmojis.first())
                result += Category(
                    id = id,
                    title = meta.title,
                    icon = meta.icon,
                    emojis = currentEmojis.toList(),
                )
            }
        }

        content.lineSequence().forEach { rawLine ->
            if (rawLine.isEmpty() || rawLine.startsWith("#")) return@forEach
            // Indented lines are skin-tone/gender variations under the base emoji.
            if (rawLine[0].isWhitespace()) return@forEach
            val line = rawLine.trimEnd()
            if (line.startsWith("[") && line.endsWith("]") && line.length > 2) {
                flushCategory()
                currentId = line.substring(1, line.length - 1)
                currentEmojis = mutableListOf()
                return@forEach
            }
            if (currentId != null) {
                val semi = line.indexOf(';')
                val emoji = (if (semi >= 0) line.substring(0, semi) else line).trim()
                if (emoji.isNotEmpty()) {
                    currentEmojis += emoji
                }
            }
        }
        flushCategory()
        return result
    }
}
