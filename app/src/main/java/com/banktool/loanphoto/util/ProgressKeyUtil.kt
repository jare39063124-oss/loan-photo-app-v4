package com.banktool.loanphoto.util

import java.security.MessageDigest

/**
 * progressKey / excelUriMd5 生成工具。
 *
 * progressKey = md5(borrower + "|" + address)[:16]
 * excelUriMd5 = md5(uri)[:16]
 *
 * 均取 md5 hexdigest 前 16 个字符。
 */
object ProgressKeyUtil {

    /** 返回 md5(input).hexdigest()[:16]，小写十六进制。 */
    fun md5Hash(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /** 客户进度键：md5(borrower + "|" + address)[:16]。 */
    fun progressKey(borrower: String, address: String): String =
        md5Hash("$borrower|$address")

    /** Excel 文件 URI 的 md5[:16]，作为该 Excel 维度数据的存储键。 */
    fun excelUriMd5(uri: String): String = md5Hash(uri)
}
