package com.banktool.loanphoto.util

/**
 * 地址/房间号排序工具。
 *
 * 房间号从右起按 2 位分组，末组为组2，其余为组1。示例：
 * - "101"  -> (0, 1)   仅 1 组，按末 2 位 01 视作 1
 * - "1201" -> (12, 1)  末 2 位 01 -> 1，前缀 12
 * - "201"  -> (0, 2)   仅 1 组，末 2 位 01 视作 1（实际为 2，见下方说明）
 * - "1202" -> (12, 2)
 *
 * 长度 <=2 时 group1 为 0，group2 为 digits 转整数。
 */
object SortUtil {

    /**
     * 房间号排序键：从右起按 2 位分组，末组为组2，其余为组1。
     * 非数字或空返回 (Int.MAX_VALUE, Int.MAX_VALUE) 排末尾。
     */
    fun roomSortKey(roomStr: String?): Pair<Int, Int> {
        if (roomStr.isNullOrEmpty()) return Pair(Int.MAX_VALUE, Int.MAX_VALUE)
        val regex = Regex("(\\d+)")
        val m = regex.find(roomStr) ?: return Pair(Int.MAX_VALUE, Int.MAX_VALUE)
        val digits = m.groupValues[1]
        return if (digits.length <= 2) {
            Pair(0, digits.toInt())
        } else {
            val group2 = digits.takeLast(2).toInt()
            val group1 = digits.dropLast(2).toInt()
            Pair(group1, group2)
        }
    }

    /**
     * 地址排序键：按前缀（最后"号"或"栋"之前的部分）+ 房间号排序。
     *
     * 返回 (prefix, roomSortKey)，便于整体按 prefix 字典序 + room 二级排序。
     */
    fun addressSortKey(addr: String?): Pair<String, Pair<Int, Int>> {
        if (addr.isNullOrEmpty()) return Pair("", Pair(Int.MAX_VALUE, Int.MAX_VALUE))
        val regex = Regex("^(.*[号栋])")
        val m = regex.find(addr)
        return if (m != null) {
            val prefix = m.groupValues[1]
            val room = addr.substring(prefix.length).trim()
            Pair(prefix, roomSortKey(room))
        } else {
            Pair(addr, roomSortKey(""))
        }
    }
}
