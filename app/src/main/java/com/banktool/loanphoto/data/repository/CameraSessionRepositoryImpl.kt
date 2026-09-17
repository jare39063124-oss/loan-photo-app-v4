package com.banktool.loanphoto.data.repository

import android.content.Context
import com.banktool.loanphoto.data.dto.CameraSessionDto
import com.banktool.loanphoto.domain.entity.CameraSession
import com.banktool.loanphoto.domain.repository.CameraSessionRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * 相机会话仓库实现，会话落盘于 `camera_session.json`（11 字段）。
 *
 * 写入为先落 `.tmp` 再经 [Files.move] 原子替换（[StandardCopyOption.ATOMIC_MOVE]），
 * 并以 [Mutex] 保护并发读写。
 */
class CameraSessionRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CameraSessionRepository {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(CameraSessionDto::class.java).indent("  ")

    private val mutex = Mutex()

    private val file: File
        get() = File(context.getExternalFilesDir(null), SESSION_FILE_NAME)

    override suspend fun saveSession(session: CameraSession) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dto = session.toDto()
                writeAtomic(adapter.toJson(dto))
            }
        }

    override suspend fun getSession(): CameraSession? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!file.exists()) return@withLock null
                try {
                    val json = file.readText(CHARSET)
                    adapter.fromJson(json)?.toEntity()
                } catch (e: Exception) {
                    Timber.w(e, "camera_session.json 解析失败")
                    null
                }
            }
        }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (file.exists()) file.delete()
        }
    }

    /** 原子写：先写 .tmp，再 ATOMIC_MOVE 覆盖。 */
    private fun writeAtomic(json: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "$SESSION_FILE_NAME.tmp")
        try {
            tmp.writeText(json, CHARSET)
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            Timber.e(e, "camera_session.json 原子写失败，退回重命名")
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val SESSION_FILE_NAME = "camera_session.json"
        val CHARSET = Charsets.UTF_8
    }
}

private fun CameraSession.toDto(): CameraSessionDto = CameraSessionDto(
    cameraLaunched = cameraLaunched,
    requestCode = requestCode,
    rowIndex = rowIndex,
    photoType = photoType,
    multiSelectKeys = multiSelectKeys,
    multiSelectRows = multiSelectRows,
    key = key,
    photoPath = photoPath,
    mediaUri = mediaUri,
    photoLaunchTime = photoLaunchTime,
    excelUriMd5 = excelUriMd5,
    timestamp = timestamp,
)

private fun CameraSessionDto.toEntity(): CameraSession = CameraSession(
    cameraLaunched = cameraLaunched,
    requestCode = requestCode,
    rowIndex = rowIndex,
    photoType = photoType,
    multiSelectKeys = multiSelectKeys,
    multiSelectRows = multiSelectRows,
    key = key,
    photoPath = photoPath,
    mediaUri = mediaUri,
    photoLaunchTime = photoLaunchTime,
    excelUriMd5 = excelUriMd5,
    timestamp = timestamp,
)
