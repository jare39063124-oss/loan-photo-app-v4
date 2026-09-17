package com.banktool.loanphoto.data.camera

import android.content.Context
import android.os.Environment
import android.widget.Toast
import com.banktool.loanphoto.domain.entity.CameraSession
import com.banktool.loanphoto.domain.repository.CameraSessionRepository
import com.banktool.loanphoto.domain.repository.ProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 相机会话恢复器。
 *
 * 在 Application.onCreate 调用 [checkAndRecover]，处理上次拍照被系统中断的情况。
 */
@Singleton
class CameraSessionRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraSessionRepository: CameraSessionRepository,
    private val progressRepository: ProgressRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 入口：检查并尝试恢复未完成的拍照会话。
     *
     * 必须在主线程调用（用于显示 Toast）；内部 IO 在 IO 线程执行。
     */
    fun checkAndRecover() {
        scope.launch {
            val session = runCatching { cameraSessionRepository.getSession() }
                .getOrNull() ?: return@launch

            if (!session.cameraLaunched) {
                Timber.d("CameraSessionRecovery: cameraLaunched=false, 跳过")
                return@launch
            }
            Timber.i("CameraSessionRecovery: 发现未完成会话 key=%s", session.key)

            val recoveredPath = recoverPath(session)
            if (recoveredPath != null) {
                onPhotoDoneWithContext(session, recoveredPath)
                showToast("已恢复上次未保存的照片")
            } else {
                showToast("上次拍照未完成，已恢复会话")
            }

            runCatching { cameraSessionRepository.clearSession() }
                .onFailure { Timber.w(it, "clearSession 失败") }
        }
    }

    /**
     * 校验 [CameraSession.photoPath] 与 DCIM/Camera 兜底。
     */
    private fun recoverPath(session: CameraSession): String? {
        session.photoPath?.let { p ->
            val f = File(p)
            if (f.exists() && f.length() > 0) {
                Timber.i("CameraSessionRecovery Step1 命中 photo_path: %s", p)
                return p
            }
        }

        val dcimCamera = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera",
        )
        if (!dcimCamera.exists()) {
            Timber.d("CameraSessionRecovery Step2 DCIM/Camera 不存在")
            return null
        }
        val launchMs = (session.photoLaunchTime * 1000).toLong()
        val cutoff = launchMs + RECOVER_WINDOW_MS
        val now = System.currentTimeMillis()
        if (now > cutoff) {
            Timber.d("CameraSessionRecovery Step2 已超过 10 分钟恢复窗口")
            return null
        }
        val candidates = dcimCamera.listFiles { file ->
            val modified = file.lastModified()
            modified >= launchMs &&
                modified <= now &&
                file.length() > MIN_RECOVER_FILE_SIZE &&
                file.extension.equals("jpg", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }

        val hit = candidates?.firstOrNull()
        if (hit != null) {
            Timber.i("CameraSessionRecovery Step2 命中 DCIM: %s", hit.absolutePath)
            return hit.absolutePath
        }
        return null
    }

    /**
     * 把恢复出的照片标记到 progress.json。
     *
     * - 主 key 走 [ProgressRepository.markPhoto]
     * - 其余 keys 走 [ProgressRepository.markPhotoBatch]
     */
    private suspend fun onPhotoDoneWithContext(session: CameraSession, photoPath: String) {
        val photoType = session.photoType
        val primaryKey = session.key
        runCatching {
            progressRepository.markPhoto(primaryKey, photoPath, photoType)
        }.onFailure { Timber.w(it, "恢复 markPhoto 失败 key=%s", primaryKey) }

        val otherKeys = session.multiSelectKeys
            .filter { it != primaryKey }
            .distinct()
        if (otherKeys.isNotEmpty()) {
            runCatching {
                progressRepository.markPhotoBatch(otherKeys, photoPath, photoType)
            }.onFailure { Timber.w(it, "恢复 markPhotoBatch 失败") }
        }
    }

    private fun showToast(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val RECOVER_WINDOW_MS = 10L * 60 * 1000 // 10 分钟
        const val MIN_RECOVER_FILE_SIZE = 10L * 1024 // 10KB
    }
}
