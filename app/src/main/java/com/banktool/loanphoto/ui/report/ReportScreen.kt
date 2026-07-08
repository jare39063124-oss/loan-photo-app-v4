package com.banktool.loanphoto.ui.report

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.banktool.loanphoto.data.dto.ReportRecord
import com.banktool.loanphoto.ui.customer.ExportResultDialog
import com.banktool.loanphoto.ui.customer.shareExportedFile
import com.banktool.loanphoto.ui.nav.NavSharedViewModel
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Bg
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.Success
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onBack: () -> Unit,
    viewModel: ReportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val progressText by viewModel.progressText.collectAsState()
    val savedSpecialLog by viewModel.specialLog.collectAsState()
    val sharedVm: NavSharedViewModel = hiltViewModel()
    val context = LocalContext.current

    // 输入框当前内容（与已保存的 specialLog 解耦：保存按钮不重置输入，
    // 取消按钮仅清空当前输入框，不删除已持久化的内容）
    var specialLogInput by rememberSaveable { mutableStateOf("") }

    // 进入页面时加载已保存的特殊日志用于回显
    LaunchedEffect(sharedVm.excelUriMd5) {
        if (sharedVm.excelUriMd5.isNotBlank()) {
            viewModel.loadSpecialLog(sharedVm.excelUriMd5)
        }
    }

    // 已保存内容变化时同步到输入框（仅首次加载 / 外部更新时同步）
    LaunchedEffect(savedSpecialLog) {
        if (specialLogInput.isEmpty() && savedSpecialLog.isNotEmpty()) {
            specialLogInput = savedSpecialLog
        }
    }

    // SAF 「保存到本地」：与 CustomerListScreen 同款模式。
    // pendingSaveFile 暂存待保存的报表文件，回调中复制到用户选择的 URI。
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    val saveToLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val file = pendingSaveFile
        if (uri != null && file != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            }.onSuccess {
                Toast.makeText(context, "已保存到本地", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "保存已取消", Toast.LENGTH_SHORT).show()
        }
        pendingSaveFile = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 日报表") },
                navigationIcon = {
                    OutlinedButton(onClick = { viewModel.reset(); onBack() }) {
                        Text("返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = TextColor
                )
            )
        },
        containerColor = Bg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (val s = state) {
                is ReportViewModel.ReportState.Idle -> {
                    ReportIdleContent(
                        specialLogInput = specialLogInput,
                        onSpecialLogChange = { specialLogInput = it },
                        onSave = {
                            viewModel.saveSpecialLog(sharedVm.excelUriMd5, specialLogInput)
                            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                        },
                        onGenerate = {
                            viewModel.generateReport(
                                customers = sharedVm.rows,
                                excelUriMd5 = sharedVm.excelUriMd5,
                                routeName = sharedVm.excelFileName.ifBlank { "default" },
                                specialLog = specialLogInput
                            )
                        },
                        onCancel = {
                            // 仅清空当前输入框，不删除已保存内容
                            specialLogInput = ""
                        }
                    )
                }

                is ReportViewModel.ReportState.Loading -> {
                    ReportLoadingContent(progressText)
                }

                is ReportViewModel.ReportState.Success -> {
                    // 仅渲染 records 预览（可选保留），结果弹窗在下方独立展示
                    ReportSuccessContent(s.records)
                    ExportResultDialog(
                        file = s.file,
                        fileSize = s.fileSize,
                        onShare = { file ->
                            shareExportedFile(context, file)
                        },
                        onSaveToLocal = { file ->
                            pendingSaveFile = file
                            saveToLauncher.launch("${file.nameWithoutExtension}.${file.extension}")
                        },
                        onDismiss = {
                            viewModel.reset()
                            // 重置后输入框保留已保存内容，便于用户重新查看
                            specialLogInput = savedSpecialLog
                        }
                    )
                }

                is ReportViewModel.ReportState.Error -> {
                    ReportErrorContent(s.message) {
                        viewModel.reset()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportIdleContent(
    specialLogInput: String,
    onSpecialLogChange: (String) -> Unit,
    onSave: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Assessment,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "AI 现场勘查日报表",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "基于拍摄记录与走访备注",
                fontSize = 14.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 补充说明 / 特殊日志输入区
            OutlinedTextField(
                value = specialLogInput,
                onValueChange = onSpecialLogChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("补充说明 / 特殊日志") },
                placeholder = {
                    Text("请输入补充说明/特殊日志（可选，生成时作为参考）")
                },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Divider,
                ),
                textStyle = TextStyle(fontSize = 14.sp, color = TextColor),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 三按钮区：保存（OutlinedButton）+ 生成日报表（Button）+ 取消（TextButton）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存")
                }
                Button(
                    onClick = onGenerate,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("生成日报表", color = Color.White)
                }
                TextButton(
                    onClick = onCancel,
                ) {
                    Text("取消", color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun ReportLoadingContent(progressText: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Accent)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = progressText.ifEmpty { "生成中..." },
            fontSize = 14.sp,
            color = TextSecondary
        )
    }
}

@Composable
private fun ReportSuccessContent(records: List<ReportRecord>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "完成（共 ${records.size} 条）",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Success
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(records) { record ->
                ReportRecordCard(record)
            }
        }
    }
}

@Composable
private fun ReportRecordCard(record: ReportRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = record.customerName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = record.collateralInfo,
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = record.fieldDescription,
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = record.riskAlert,
                fontSize = 13.sp,
                color = if (record.riskAlert.contains("no risk")) Success else Error
            )
        }
    }
}

@Composable
private fun ReportErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "生成失败",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            fontSize = 14.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Text("重试", color = Color.White)
        }
    }
}
