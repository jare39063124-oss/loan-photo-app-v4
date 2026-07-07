package com.banktool.loanphoto.domain.repository

import com.banktool.loanphoto.domain.entity.CameraSession

/**
 * 相机会话持久化仓库（camera_session.json）。
 *
 * 用于应用被系统杀死后恢复拍照上下文。
 */
interface CameraSessionRepository {

    /** 保存相机会话（覆盖式原子写）。 */
    suspend fun saveSession(session: CameraSession)

    /** 读取当前相机会话，无则 null。 */
    suspend fun getSession(): CameraSession?

    /** 清除当前相机会话。 */
    suspend fun clearSession()
}
