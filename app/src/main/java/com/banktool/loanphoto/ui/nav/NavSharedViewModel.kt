package com.banktool.loanphoto.ui.nav

import androidx.lifecycle.ViewModel
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.session.PhotoSessionHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Activity 作用域的共享 ViewModel，包装 [PhotoSessionHolder]。
 *
 * 供 NavHost 和各 Screen 通过 `hiltViewModel<NavSharedViewModel>()` 获取，
 * 读取跨路由共享数据（选中行、Excel URI 等）。
 *
 * 为什么不直接注入 PhotoSessionHolder 到 Screen？
 * —— Composable 不能直接 @Inject，需通过 hiltViewModel 间接访问。
 */
@HiltViewModel
class NavSharedViewModel @Inject constructor(
    private val sessionHolder: PhotoSessionHolder,
) : ViewModel() {

    val rows: List<CustomerRow> get() = sessionHolder.rows

    val excelUri: String get() = sessionHolder.excelUri

    val excelFileName: String get() = sessionHolder.excelFileName

    val excelUriMd5: String get() = sessionHolder.excelUriMd5

    fun setPhotoRows(rows: List<CustomerRow>) {
        sessionHolder.setPhotoRows(rows)
    }
}
