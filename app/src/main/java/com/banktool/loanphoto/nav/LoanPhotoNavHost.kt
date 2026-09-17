package com.banktool.loanphoto.nav

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.banktool.loanphoto.ui.assistant.ChatScreen
import com.banktool.loanphoto.ui.camera.CameraScreen
import com.banktool.loanphoto.ui.customer.CustomerListScreen
import com.banktool.loanphoto.ui.nav.NavSharedViewModel
import com.banktool.loanphoto.ui.progress.ProgressScreen
import com.banktool.loanphoto.ui.report.ReportScreen
import com.banktool.loanphoto.ui.settings.SettingsScreen

object Routes {
    const val CUSTOMER_LIST = "customer_list"
    const val CAMERA = "camera"
    const val PROGRESS = "progress"
    const val REPORT = "report"
    const val ASSISTANT = "assistant"
    const val SETTINGS = "settings"
}

/**
 * 主导航
 * customer_list → camera → progress → report → assistant
 *
 * 跨路由数据传递：CustomerRow 非 Parcelable，通过 [NavSharedViewModel]
 * （Activity 作用域）桥接。CustomerListViewModel 在 loadExcel 时写入
 * PhotoSessionHolder，NavHost 在 onTakePhoto 回调中写入选中行。
 */
@Composable
fun LoanPhotoNavHost() {
    val navController = rememberNavController()
    val sharedVm: NavSharedViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = Routes.CUSTOMER_LIST,
    ) {
        composable(Routes.CUSTOMER_LIST) {
            CustomerListScreen(
                onImportExcel = {
                    // SAF 已在 CustomerListScreen 内部实现，此回调保留为外部 hook
                },
                onGenerateReport = { navController.navigate(Routes.REPORT) },
                onTakePhoto = { rows ->
                    sharedVm.setPhotoRows(rows)
                    navController.navigate(Routes.CAMERA)
                },
                onViewProgress = {
                    navController.navigate(Routes.PROGRESS)
                },
                onOpenAssistant = {
                    navController.navigate(Routes.ASSISTANT)
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
            )
        }
        composable(Routes.CAMERA) {
            CameraScreen(
                rows = sharedVm.rows,
                excelUri = sharedVm.excelUri,
                onClose = { navController.popBackStack() },
            )
        }
        composable(Routes.PROGRESS) {
            ProgressScreen(
                excelUri = sharedVm.excelUri,
                fileName = sharedVm.excelFileName,
                onBack = { navController.popBackStack() },
                onGenerateReport = { navController.navigate(Routes.REPORT) },
            )
        }
        composable(Routes.REPORT) {
            ReportScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ASSISTANT) {
            ChatScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
