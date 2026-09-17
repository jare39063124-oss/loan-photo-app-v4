package com.banktool.loanphoto.data.naming

import com.banktool.loanphoto.domain.entity.CustomerRow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NamingRuleGenerator] 文件名字节预算截断测试。
 *
 * 背景：超长地址/客户名曾使文件名 UTF-8 达约 256 字节，超过 Android 文件系统
 * NAME_MAX=255 导致保存崩溃。生成器现保证最终文件名 ≤240 字节，
 * 且尾部后缀 `-照片类型-NN.jpg` 完整保留（CameraViewModel.calculateNextSequence
 * 依赖文件名包含 `-${类型名}-` 统计同类型张数）。
 */
class NamingRuleGeneratorTest {

    private val generator = NamingRuleGenerator()

    /** 固定时间戳，避免测试受运行时间影响（DATE 段格式化为 yyyyMMdd）。 */
    private val fixedTimestamp = 1737000000000L

    @Test
    fun `超长真实条目生成名不超过240字节且后缀完整`() {
        // 日志中的真实崩溃条目（盘锦某石化公司，地址含产权证号等超长拼接）
        val row = CustomerRow(
            rowIndex = 1,
            serial = "968.95",
            borrower = "盘锦焱诚石油化工有限公司",
            addrGeneral = "盘锦市兴隆台区河畔小区文化广场文化广场单元9号",
            addrDetail = "，1-3层，南北，面积968.95平方米；968.95-辽（2022）盘锦市不动产权第7000564号-盘锦鸿图石化贸易有限公司",
            propertyType = "商业用房",
            remark = "",
            progressKey = "test000000000001",
        )

        val name = generator.generate(
            config = NamingConfig(), // 默认 DATE-BORROWER-ADDRESS-NONE
            customerRow = row,
            photoTypeDisplayName = "远景",
            sequence = 1,
            timestamp = fixedTimestamp,
        )

        val bytes = name.toByteArray(Charsets.UTF_8).size
        assertTrue("生成名 $bytes 字节超过 240 预算: $name", bytes <= 240)
        assertTrue("尾部后缀被截断: $name", name.endsWith("-远景-01.jpg"))
        assertTrue("缺少类型分隔标记（calculateNextSequence 依赖）: $name", name.contains("-远景-"))
    }

    @Test
    fun `正常短名不受截断影响`() {
        val row = CustomerRow(
            rowIndex = 0,
            serial = "1",
            borrower = "张三",
            addrGeneral = "盘锦市兴隆台区",
            addrDetail = "河畔小区1号",
            propertyType = "住宅",
            remark = "",
            progressKey = "test000000000002",
        )

        val name = generator.generate(
            config = NamingConfig(),
            customerRow = row,
            photoTypeDisplayName = "远景",
            sequence = 1,
            timestamp = fixedTimestamp,
        )

        // 日期段因时区实现而异，仅校验 8 位数字；其余段必须原样保留
        assertTrue(
            "正常短名被意外截断: $name",
            name.matches(Regex("""\d{8}-张三-盘锦市兴隆台区河畔小区1号-远景-01\.jpg""")),
        )
    }

    @Test
    fun `超预算时优先截断地址段并保留客户名段`() {
        val longBorrower = "某借款人公司名称".repeat(5) // 8 字 × 5 × 3B = 120 字节
        val longAddress = "超长地址描述文本".repeat(30) // 8 字 × 30 × 3B = 720 字节
        val row = CustomerRow(
            rowIndex = 2,
            serial = "1",
            borrower = longBorrower,
            addrGeneral = longAddress,
            addrDetail = "",
            propertyType = "",
            remark = "",
            progressKey = "test000000000003",
        )

        val name = generator.generate(
            config = NamingConfig(), // DATE(8B)+BORROWER(120B)+ADDRESS(720B) → 超预算，截最长段(地址)
            customerRow = row,
            photoTypeDisplayName = "远景",
            sequence = 2,
            timestamp = fixedTimestamp,
        )

        assertTrue("生成名超过 240 字节: $name", name.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue("地址段优先截断时客户名段应完整保留: $name", name.contains(longBorrower))
        assertTrue("尾部后缀被截断: $name", name.endsWith("-远景-02.jpg"))
    }

    @Test
    fun `地址段截到极限后继续截断其他段仍不超预算`() {
        // 地址段极短(9B)、借款人段超长：最长段(借款人)吸收全部超额，短地址段完整保留
        val longBorrower = "借款人超长名称不断重复拼接测试".repeat(20) // 14 字 × 20 × 3B = 840 字节
        val row = CustomerRow(
            rowIndex = 3,
            serial = "1",
            borrower = longBorrower,
            addrGeneral = "盘锦市",
            addrDetail = "",
            propertyType = "",
            remark = "",
            progressKey = "test000000000004",
        )

        val name = generator.generate(
            config = NamingConfig(),
            customerRow = row,
            photoTypeDisplayName = "内部",
            sequence = 12,
            timestamp = fixedTimestamp,
        )

        assertTrue("生成名超过 240 字节: $name", name.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue("尾部后缀被截断: $name", name.endsWith("-内部-12.jpg"))
        assertTrue("地址段（最短中段）不应被截空: $name", name.contains("盘锦市"))
    }

    @Test
    fun `CUSTOM短文本段生成名包含该文本`() {
        val row = CustomerRow(
            rowIndex = 4,
            serial = "1",
            borrower = "张三",
            addrGeneral = "盘锦市兴隆台区",
            addrDetail = "",
            propertyType = "",
            remark = "",
            progressKey = "test000000000005",
        )
        // 段1=CUSTOM("押品")，段2=客户名，段3/4=NONE
        val config = NamingConfig(
            segment1 = NameSegment.CUSTOM,
            customText1 = "押品",
            segment2 = NameSegment.BORROWER,
        )

        val name = generator.generate(
            config = config,
            customerRow = row,
            photoTypeDisplayName = "远景",
            sequence = 1,
            timestamp = fixedTimestamp,
        )

        assertTrue("生成名缺少自定义文本: $name", name.contains("押品"))
        assertTrue("生成名缺少客户名段: $name", name.contains("张三"))
        assertTrue("尾部后缀被截断: $name", name.endsWith("-远景-01.jpg"))
    }

    @Test
    fun `CUSTOM超长文本段生成名不超过240字节且后缀完整`() {
        val row = CustomerRow(
            rowIndex = 5,
            serial = "1",
            borrower = "李四",
            addrGeneral = "",
            addrDetail = "",
            propertyType = "",
            remark = "",
            progressKey = "test000000000006",
        )
        // 超长自定义文本：7 字 × 3B × 50 = 1050 字节，远超 240 字节预算
        val longCustom = "自定义文本内容测试用".repeat(50)
        val config = NamingConfig(
            segment1 = NameSegment.CUSTOM,
            customText1 = longCustom,
            segment2 = NameSegment.BORROWER,
        )

        val name = generator.generate(
            config = config,
            customerRow = row,
            photoTypeDisplayName = "远景",
            sequence = 3,
            timestamp = fixedTimestamp,
        )

        assertTrue("生成名超过 240 字节: $name", name.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue("尾部后缀被截断: $name", name.endsWith("-远景-03.jpg"))
        assertTrue("缺少类型分隔标记（calculateNextSequence 依赖）: $name", name.contains("-远景-"))
        // 截断按段前缀进行，前缀内容应保留
        assertTrue("自定义文本前缀未保留: $name", name.startsWith(longCustom.take(10)))
    }
}
