package com.banktool.loanphoto.util

import android.os.Build
import java.util.Locale

object DeviceDetector {
    fun isHuaweiDevice(): Boolean {
        val mfr = Build.MANUFACTURER?.uppercase(Locale.ROOT) ?: ""
        return mfr == "HUAWEI" || mfr == "HONOR"
    }
}
