package com.banktool.loanphoto.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.datasource.ReportTemplateFiller
import com.banktool.loanphoto.data.dto.ReportRecord
import com.banktool.loanphoto.data.repository.AiRepository
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoRecord
import com.banktool.loanphoto.domain.repository.ProgressRepository
import com.banktool.loanphoto.domain.repository.VisitNoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val progressRepository: ProgressRepository,
    private val visitNoteRepository: VisitNoteRepository,
    private val reportTemplateFiller: ReportTemplateFiller
) : ViewModel() {

    sealed class ReportState {
        object Idle : ReportState()
        object Loading : ReportState()
        data class Success(val records: List<ReportRecord>, val outputPath: String) : ReportState()
        data class Error(val message: String) : ReportState()
    }

    private val _state = MutableStateFlow<ReportState>(ReportState.Idle)
    val state: StateFlow<ReportState> = _state.asStateFlow()

    private val _progressText = MutableStateFlow("")
    val progressText: StateFlow<String> = _progressText.asStateFlow()

    /**
     * 生成日报表
     * @param customers 所有客户列表
     * @param excelUriMd5 Excel URI 的 MD5
     * @param routeName 线路名称
     */
    fun generateReport(
        customers: List<CustomerRow>,
        excelUriMd5: String,
        routeName: String
    ) {
        viewModelScope.launch {
            _state.value = ReportState.Loading
            _progressText.value = "正在收集拍摄记录..."

            try {
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

                // 4. 调用 AI 生成报表
                _progressText.value = "AI 正在生成报告..."
                val records = aiRepository.generateReport(
                    visitedRecords = visitedRecords,
                    batchMarkedCount = batchMarkedCount,
                    visitNote = visitNote
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

                _progressText.value = "报告已生成"
                _state.value = ReportState.Success(records, outputPath)

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
