package com.banktool.loanphoto.ui.customer.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 最近打开的 Excel 文件存储。
 *
 * 使用 [preferencesDataStore] 持久化最近 5 条记录，序列化格式为 JSON 数组：
 * ```json
 * [{"uri":"content://...","fileName":"客户清单.xlsx","timestamp":1730000000000}]
 * ```
 *
 * 不依赖 Moshi（避免 codegen/反射配置），直接使用 Android 内置 [JSONArray]/[JSONObject]。
 */
private val Context.recentFilesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recent_excel_files",
)

/**
 * 最近文件存储条目（原始数据，不含数据指示器）。
 */
data class RecentFile(
    val uri: String,
    val fileName: String,
    val timestamp: Long,
)

@Singleton
class RecentFilesStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 读取最近文件列表（按时间倒序，最多 [MAX_RECENT] 条）。
     */
    suspend fun getRecentFiles(): List<RecentFile> = withContext(Dispatchers.IO) {
        val prefs = context.recentFilesDataStore.data.first()
        val json = prefs[KEY_RECENT_FILES] ?: return@withContext emptyList()
        runCatching { decode(json) }.getOrElse {
            Timber.w(it, "recent_excel_files JSON 解析失败")
            emptyList()
        }
    }

    /**
     * 添加/更新一条最近文件记录（相同 uri 视为同一条，置顶）。
     * 若 [fileName] 为空，则尝试通过 [DocumentFile] 解析文件名。
     */
    suspend fun addRecent(uri: String, fileName: String) = withContext(Dispatchers.IO) {
        val resolvedName = fileName.ifBlank { resolveFileName(uri) }
        context.recentFilesDataStore.edit { prefs ->
            val current = runCatching { decode(prefs[KEY_RECENT_FILES] ?: "[]") }.getOrDefault(emptyList())
            val updated = (listOf(RecentFile(uri, resolvedName, System.currentTimeMillis())) +
                current.filter { it.uri != uri }).take(MAX_RECENT)
            prefs[KEY_RECENT_FILES] = encode(updated)
        }
    }

    /**
     * 删除指定 uri 的记录（用于清理失效权限）。
     */
    suspend fun removeRecent(uri: String) = withContext(Dispatchers.IO) {
        context.recentFilesDataStore.edit { prefs ->
            val current = runCatching { decode(prefs[KEY_RECENT_FILES] ?: "[]") }.getOrDefault(emptyList())
            val updated = current.filter { it.uri != uri }
            prefs[KEY_RECENT_FILES] = encode(updated)
        }
    }

    private fun resolveFileName(uri: String): String {
        return runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.name
        }.getOrNull() ?: "未知文件"
    }

    private fun encode(list: List<RecentFile>): String {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put(KEY_URI, item.uri)
                    put(KEY_FILE_NAME, item.fileName)
                    put(KEY_TIMESTAMP, item.timestamp)
                },
            )
        }
        return arr.toString()
    }

    private fun decode(json: String): List<RecentFile> {
        val arr = JSONArray(json)
        val result = ArrayList<RecentFile>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                RecentFile(
                    uri = obj.optString(KEY_URI),
                    fileName = obj.optString(KEY_FILE_NAME, "未知文件"),
                    timestamp = obj.optLong(KEY_TIMESTAMP, 0L),
                ),
            )
        }
        return result
    }

    private companion object {
        const val MAX_RECENT = 5
        val KEY_RECENT_FILES = stringPreferencesKey("recent_excel_json")
        const val KEY_URI = "uri"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
