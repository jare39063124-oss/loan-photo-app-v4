package com.banktool.loanphoto.ui.customer

import android.content.Context
import org.json.JSONArray

/**
 * 搜索历史存储（最近 [MAX_HISTORY] 条，一/二级搜索栏共用）。
 * 持久化到 SharedPreferences("search_prefs")，值为 JSON 字符串数组（最新在前、已去重）。
 * 数据量极小，同步读写即可。
 */
class SearchHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取搜索历史（最新在前，最多 [MAX_HISTORY] 条）。 */
    fun getHistory(): List<String> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** 写入一条搜索记录：去重、最新置顶、最多保留 [MAX_HISTORY] 条；空白串忽略。 */
    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val updated = (listOf(q) + getHistory().filter { it != q }).take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, encode(updated)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun encode(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private companion object {
        const val PREFS_NAME = "search_prefs"
        const val KEY_HISTORY = "search_history"
        const val MAX_HISTORY = 5
    }
}
