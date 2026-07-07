package com.banktool.loanphoto.domain.session

import com.banktool.loanphoto.domain.entity.CustomerRow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨路由共享的拍照会话状态。
 *
 * CustomerRow 不是 Parcelable，无法通过 SavedStateHandle/路由参数传递，
 * 因此用 @Singleton 桥接 CustomerListScreen → Camera/Progress/Report。
 *
 * - [excelUri]/[excelFileName]/[excelUriMd5] 由 CustomerListViewModel.loadExcel 设置
 * - [rows] 由 NavHost 的 onTakePhoto 回调设置（用户选中的行）
 *
 * 生命周期：进程级单例。数据在 Activity 重建后保留，进程销毁后丢失
 * （可接受——用户重新导入 Excel 即可恢复）。
 */
@Singleton
class PhotoSessionHolder @Inject constructor() {

    @Volatile
    var rows: List<CustomerRow> = emptyList()

    @Volatile
    var excelUri: String = ""

    @Volatile
    var excelFileName: String = ""

    @Volatile
    var excelUriMd5: String = ""

    /**
     * 设置拍照目标行（用户从客户列表选中后调用）。
     */
    fun setPhotoRows(rows: List<CustomerRow>) {
        this.rows = rows
    }

    /**
     * 设置 Excel 文件信息（loadExcel 成功后调用）。
     */
    fun setExcelInfo(uri: String, fileName: String, md5: String) {
        this.excelUri = uri
        this.excelFileName = fileName
        this.excelUriMd5 = md5
    }
}
