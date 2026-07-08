package com.banktool.loanphoto.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.datasource.ReportTemplateFiller
import com.banktool.loanphoto.data.dto.ReportRecord
import com.banktool.loanphoto.data.repository.AiRepository
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoRecord
import com.banktool.loanphoto.domain.repository.ProgressRepository
import com.banktool.loanphoto.domain.repository.SpecialLogRepository
import com.banktool.loanphoto.domain.repository.VisitNoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val progressRepository: ProgressRepository,
    private val visitNoteRepository: VisitNoteRepository,
    private val specialLogRepository: SpecialLogRepository,
    private val reportTemplateFiller: ReportTemplateFiller
) : ViewModel() {

    sealed class ReportState {
        object Idle : ReportState()
        object Loading : ReportState()
        data class Success(
            val records: List<ReportRecord>,
            val file: File,
            val fileSize: Long,
        ) : ReportState()
        data class Error(val message: String) : ReportState()
    }

    private val _state = MutableStateFlow<ReportState>(ReportState.Idle)
    val state: StateFlow<ReportState> = _state.asStateFlow()

    private val _progressText = MutableStateFlow("")
    val progressText: StateFlow<String> = _progressText.asStateFlow()

    /** 已保存的特殊日志内容，进入页面时回显到输入框。 */
    private val _specialLog = MutableStateFlow("")
    val specialLog: StateFlow<String> = _specialLog.asStateFlow()

    /**
     * 进入页面时加载已保存的特殊日志用于回显。
     *
     * @param excelUriMd5 Excel URI 的 MD5，作为特殊日志的存储 key
     */
    fun loadSpecialLog(excelUriMd5: String) {
        viewModelScope.launch {
            try {
                _specialLog.value = specialLogRepository.getSpecialLog(excelUriMd5)
            } catch (e: Exception) {
                Timber.w(e, "加载特殊日志失败: %s", excelUriMd5)
            }
        }
    }

    /**
     * 仅保存特殊日志，不触发生成。
     *
     * @param excelUriMd5 Excel URI 的 MD5
     * @param content 用户输入的特殊日志内容
     */
    fun saveSpecialLog(excelUriMd5: String, content: String) {
        viewModelScope.launch {
            try {
                specialLogRepository.saveSpecialLog(excelUriMd5, content)
                _specialLog.value = content
            } catch (e: Exception) {
                Timber.e(e, "保存特殊日志失败: %s", excelUriMd5)
            }
        }
    }

    /**
     * 生成日报表。
     *
     * 流程：
     * 1. 先持久化特殊日志（与「保存」按钮一致，避免生成失败时丢失输入）
     * 2. 收集已拍摄客户记录
     * 3. 读取走访备注
     * 4. 调用 AI 生成报表（携带 specialLog）
     * 5. 校验 AI 是否参考了走访备注
     * 6. 填充模板，得到输出文件路径
     * 7. 将路径转为 [File]，记录 file + fileSize 到 Success 状态
     *
     * @param customers 所有客户列表
     * @param excelUriMd5 Excel URI 的 MD5
     * @param routeName 线路名称
     * @param specialLog 用户输入的特殊日志（可选，null/空串均视为无）
     */
    fun generateReport(
        customers: List<CustomerRow>,
        excelUriMd5: String,
        routeName: String,
        specialLog: String? = null,
    ) {
        viewModelScope.launch {
            _state.value = ReportState.Loading
            _progressText.value = "正在收集拍摄记录..."

            try {
                // 0. 先持久化特殊日志（无论是否为空都覆盖保存，保持与「保存」按钮语义一致）
                val normalizedLog = specialLog?.trim().orEmpty()
                runCatching {
                    specialLogRepository.saveSpecialLog(excelUriMd5, normalizedLog)
                    _specialLog.value = normalizedLog
                }.onFailure { Timber.w(it, "生成前保存特殊日志失败") }

                // 1. 收集已拍摄客户记录
                val allProgress = progressRepository.getAllProgress()
                val visitedRecords = mutableListOf<Pair<CustomerRow, PhotoRecord>>()

                for (customer in customers) {
                    val photoRecord = allProgress[customer.progressKey]
                    if (photoRecord != null && photoRecord.photos.isNotEmpty()) {
                        visitedRecords.add(customer to photoRecord)
                    }
                }

                if (visitedRecords.isEmpty()) {
                    _state.value = ReportState.Error("没有已拍摄的客户记录，无法生成报表")
                    return@launch
                }

                _progressText.value = "共 ${visitedRecords.size} 个已拍摄客户，正在调用 AI..."

                // 2. 获取 batch_marked
                val batchMarkedKeys = progressRepository.getBatchMarkedKeys()
                val batchMarkedCount = visitedRecords.count { (row, _) ->
                    batchMarkedKeys.contains(row.progressKey)
                }

                // 3. 获取 visit_note
                val visitNote = visitNoteRepository.getVisitNote(excelUriMd5)

                // 4. 调用 AI 生成报表（携带 specialLog）
                _progressText.value = "AI 正在生成报告..."
                val records = aiRepository.generateReport(
                    visitedRecords = visitedRecords,
                    batchMarkedCount = batchMarkedCount,
                    visitNote = visitNote,
                    specialLog = normalizedLog.ifEmpty { null }
                )

                // 5. 校验 AI 是否参考了 visit_note
                val isValid = aiRepository.validateResponse(records, visitNote)
                if (!isValid) {
                    Timber.w("AI response validation failed: low visit_note reference")
                }

                _progressText.value = "正在填充报表模板..."

                // 6. 填充模板
                val outputPath = reportTemplateFiller.fillTemplate(
                    records = records,
                    routeName = routeName,
                    batchMarkedCount = batchMarkedCount
                )

                // 7. 转为 File，记录 file + fileSize
                val outputFile = File(outputPath)
                val fileSize = if (outputFile.exists()) outputFile.length() else 0L

                _progressText.value = "报告已生成"
                _state.value = ReportState.Success(records, outputFile, fileSize)

            } catch (e: Exception) {
                Timber.e(e, "Report generation failed")
                _state.value = ReportState.Error(e.message ?: "生成报表失败")
            }
        }
    }

    fun reset() {
        _state.value = ReportState.Idle
        _progressText.value = ""
    }
}
