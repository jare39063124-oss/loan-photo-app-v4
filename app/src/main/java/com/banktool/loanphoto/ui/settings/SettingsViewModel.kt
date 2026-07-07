package com.banktool.loanphoto.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.naming.NameSegment
import com.banktool.loanphoto.data.naming.NamingConfig
import com.banktool.loanphoto.data.naming.NamingConfigRepository
import com.banktool.loanphoto.data.naming.NamingRuleGenerator
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * 设置页 ViewModel。
 *
 * 职责：
 * 1. 暴露命名规则配置 [namingConfig]（DataStore 持久化，实时响应）
 * 2. 提供段设置入口 [setSegment]
 * 3. 生成实时预览文件名 [previewFileName]
 *
 * 注入 [NamingConfigRepository] 与 [NamingRuleGenerator]（均 @Singleton）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val namingConfigRepository: NamingConfigRepository,
    private val namingRuleGenerator: NamingRuleGenerator,
) : ViewModel() {

    /** 命名规则配置（初始值全 NONE，订阅 DataStore 后立即更新为持久化值）。 */
    val namingConfig: StateFlow<NamingConfig> = namingConfigRepository.configFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, NamingConfig())

    /**
     * 设置某一段命名配置并持久化。
     *
     * @param index 段索引（0..3）
     * @param segment 新的段类型
     */
    fun setSegment(index: Int, segment: NameSegment) {
        viewModelScope.launch {
            namingConfigRepository.setSegment(index, segment)
        }
    }

    /**
     * 根据当前配置生成示例文件名（用于设置页实时预览）。
     *
     * 使用固定的 mock 客户数据（borrower="成都投资集团"、addrGeneral="和平区"、
     * addrDetail="XX街123号1430"）、[PhotoType.DISTANT]（远景）、sequence=1，
     * DATE 段取今日日期。
     */
    fun previewFileName(config: NamingConfig): String {
        val mockCustomer = CustomerRow(
            rowIndex = 0,
            serial = "1",
            borrower = "成都投资集团",
            addrGeneral = "和平区",
            addrDetail = "XX街123号1430",
            propertyType = "",
            remark = "",
            progressKey = "",
        )
        return namingRuleGenerator.generate(
            config = config,
            customerRow = mockCustomer,
            photoType = PhotoType.DISTANT,
            sequence = 1,
            timestamp = todayNoonTimestamp(),
        )
    }

    /** 取今日正午时间戳（保证 yyyyMMdd 在设备本地时区下稳定显示为今日）。 */
    private fun todayNoonTimestamp(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
