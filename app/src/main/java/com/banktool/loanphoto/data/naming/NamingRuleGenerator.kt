package com.banktool.loanphoto.data.naming

import com.banktool.loanphoto.domain.entity.CustomerRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照片命名规则生成器。
 *
 * 根据 [NamingConfig] 将客户信息按段拼接为文件名，末尾固定追加
 * `${photoTypeDisplayName}-${sequence:02d}.jpg`。
 *
 * 生成流程：遍历 [NamingConfig.segments]，跳过 [NameSegment.NONE] 与取值为空的段
 * （DATE→yyyyMMdd、BORROWER→borrower、SERIAL→serial、ADDRESS→addrGeneral+addrDetail、
 * CUSTOM→config 对应段 customTextN，空白跳过），
 * 将保留的非空段用 `-` 连接并追加后缀；全部为空时回退 `IMG_<timestamp>.jpg`；
 * 最后将非法字符 `/ \ : * ? " < > |` 替换为 `_`。
 *
 * 字节预算：最终文件名 UTF-8 总长 ≤ [MAX_NAME_BYTES]（240）字节，防止超过 Android
 * 文件系统 NAME_MAX=255 导致拍保存失败。超预算时按优先级截断中段
 * （地址段优先，其次最长段），尾部后缀完整保留，截断按 Unicode code point 边界进行。
 */
@Singleton
class NamingRuleGenerator @Inject constructor() {

    /** 文件名非法字符集合。 */
    private val illegalChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    }

    /**
     * 生成照片文件名。
     *
     * @param config 命名规则配置（4 段）
     * @param customerRow 客户行数据
     * @param photoTypeDisplayName 拍照类型显示名（同时作为文件名片段与 progress.json key，
     *        支持用户自定义类型）
     * @param sequence 序号（1-based，格式化为两位）
     * @param timestamp 时间戳（用于 DATE 段与回退名），默认当前时间
     * @return 符合命名规则的安全文件名
     */
    fun generate(
        config: NamingConfig,
        customerRow: CustomerRow,
        photoTypeDisplayName: String,
        sequence: Int,
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        val customTexts = config.customTexts
        val entries = buildList {
            config.segments.forEachIndexed { index, segment ->
                val value = buildSegment(segment, customerRow, timestamp, customTexts[index])
                if (value.isNotEmpty()) {
                    add(segment to value)
                }
            }
        }

        val name = if (entries.isEmpty()) {
            // 全 NONE 或所有非 NONE 段数据为空 → 回退
            "IMG_${timestamp}.jpg"
        } else {
            val suffix = "$photoTypeDisplayName-${String.format("%02d", sequence)}.jpg"
            fitPartsToByteBudget(entries, suffix)
        }

        return sanitize(name)
    }

    /**
     * 计算单个段的字符串值（NONE / CUSTOM 空白文本返回空串，空串段在 [generate] 中被跳过）。
     */
    private fun buildSegment(
        segment: NameSegment,
        row: CustomerRow,
        timestamp: Long,
        customText: String,
    ): String = when (segment) {
        NameSegment.DATE -> dateFormat.get()!!.format(Date(timestamp))
        NameSegment.BORROWER -> row.borrower
        NameSegment.SERIAL -> row.serial
        NameSegment.ADDRESS -> row.addrGeneral + row.addrDetail
        NameSegment.CUSTOM -> customText.trim()
        NameSegment.NONE -> ""
    }

    /** 将文件名中的非法字符替换为 `_`。 */
    private fun sanitize(name: String): String {
        if (name.none { it in illegalChars }) return name
        val sb = StringBuilder(name.length)
        for (c in name) {
            sb.append(if (c in illegalChars) '_' else c)
        }
        return sb.toString()
    }

    /**
     * 截断中段 parts，使 `中段 + "-" + suffix` 的 UTF-8 总字节数 ≤ [MAX_NAME_BYTES]。
     *
     * 未超预算时原样拼接返回。超预算时贪婪截断：每轮选取当前字节最长的非空中段
     * （同等长度时地址段优先），截到「恰好满足总预算」的最长前缀；实践中地址段通常
     * 最长即被优先截断，且不会把短段无意义截空（短段保留，超额由长段吸收）。
     * 某段截到空仍超时继续下一轮截最长段；全部中段截空仍超（仅 suffix 自身超长的
     * 理论场景）由兜底硬截保证不超。
     * 尾部后缀 `-${类型名}-${NN}.jpg` 永远完整保留——CameraViewModel.calculateNextSequence
     * 依赖文件名包含 `-${类型名}-`。
     *
     * @param entries 中段列表（segment → 段值），均已非空
     * @param suffix 尾部后缀（`类型名-NN.jpg`）
     * @return 拼接后的完整文件名
     */
    private fun fitPartsToByteBudget(entries: List<Pair<NameSegment, String>>, suffix: String): String {
        val parts = entries.map { it.second }.toMutableList()
        val suffixBytes = ("-$suffix").toByteArray(Charsets.UTF_8).size
        val bodyBudget = MAX_NAME_BYTES - suffixBytes

        // 中段字节数 = 非空段字节和 + 分隔符数（空段过滤后 join，避免出现连续 "--"）
        fun bodyBytes(): Int =
            parts.filter { it.isNotEmpty() }.let { nonEmpty ->
                nonEmpty.sumOf { it.toByteArray(Charsets.UTF_8).size } + nonEmpty.size - 1
            }

        fun joinBody(): String = parts.filter { it.isNotEmpty() }.joinToString("-")

        if (bodyBytes() <= bodyBudget) {
            return joinBody() + "-" + suffix
        }

        while (bodyBytes() > bodyBudget) {
            val excess = bodyBytes() - bodyBudget
            val index = parts.indices
                .filter { parts[it].isNotEmpty() }
                .maxByOrNull {
                    val bytes = parts[it].toByteArray(Charsets.UTF_8).size
                    val addressBonus = if (entries.getOrNull(it)?.first == NameSegment.ADDRESS) 1 else 0
                    bytes * 10 + addressBonus
                } ?: break
            val currentBytes = parts[index].toByteArray(Charsets.UTF_8).size
            parts[index] = truncateToByteLimit(parts[index], currentBytes - excess)
        }

        if (bodyBytes() > bodyBudget) {
            val lastIndex = parts.indices.lastOrNull { parts[it].isNotEmpty() }
            if (lastIndex != null) {
                val excess = bodyBytes() - bodyBudget
                val currentBytes = parts[lastIndex].toByteArray(Charsets.UTF_8).size
                parts[lastIndex] = truncateToByteLimit(parts[lastIndex], currentBytes - excess)
            }
        }

        val nonEmpty = parts.filter { it.isNotEmpty() }
        return if (nonEmpty.isEmpty()) suffix else nonEmpty.joinToString("-") + "-" + suffix
    }

    /**
     * 按 UTF-8 字节预算截断字符串前缀（code point 边界，不截半个中文/emoji）。
     *
     * @param maxBytes 允许的最大字节数，≤0 返回空串
     */
    private fun truncateToByteLimit(s: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder(s.length)
        var used = 0
        var index = 0
        while (index < s.length) {
            val codePoint = s.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val piece = s.substring(index, index + charCount)
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (used + pieceBytes > maxBytes) break
            sb.append(piece)
            used += pieceBytes
            index += charCount
        }
        return sb.toString()
    }

    private companion object {
        /**
         * 文件名字节预算：Android 文件系统 NAME_MAX=255，留余量取 240，
         * 兼容多字节文件系统的元数据开销。
         */
        const val MAX_NAME_BYTES = 240
    }
}
