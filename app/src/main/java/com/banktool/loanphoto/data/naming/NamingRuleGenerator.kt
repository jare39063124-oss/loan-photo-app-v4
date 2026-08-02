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
 * 规则：
 * 1. 遍历 [NamingConfig.segments]，跳过 [NameSegment.NONE] 段；
 * 2. 各段取值（DATE→yyyyMMdd、BORROWER→borrower、SERIAL→serial、ADDRESS→addrGeneral+addrDetail），
 *    若取值为空字符串则同样跳过该段；
 * 3. 将保留的非空段用 `-` 连接，再追加 `-` + 后缀；
 * 4. 若所有段均被跳过（全 NONE 或数据全空），回退 `IMG_<timestamp>.jpg`；
 * 5. 去除文件名中的非法字符 `/ \ : * ? " < > |`，替换为 `_`。
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
        val parts = buildList {
            for (segment in config.segments) {
                val value = buildSegment(segment, customerRow, timestamp)
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }

        val name = if (parts.isEmpty()) {
            // 全 NONE 或所有非 NONE 段数据为空 → 回退
            "IMG_${timestamp}.jpg"
        } else {
            val suffix = "$photoTypeDisplayName-${String.format("%02d", sequence)}.jpg"
            parts.joinToString("-") + "-" + suffix
        }

        return sanitize(name)
    }

    /** 计算单个段的字符串值（NONE 返回空串，空串段在 [generate] 中被跳过）。 */
    private fun buildSegment(segment: NameSegment, row: CustomerRow, timestamp: Long): String =
        when (segment) {
            NameSegment.DATE -> dateFormat.get()!!.format(Date(timestamp))
            NameSegment.BORROWER -> row.borrower
            NameSegment.SERIAL -> row.serial
            NameSegment.ADDRESS -> row.addrGeneral + row.addrDetail
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
}
