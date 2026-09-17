# 资产盘点拍照工具 v4.0

> 抚顺银行风险管理部 — 抵押物现场拍照取证工具
> 原生 Android 实现（Kotlin + Jetpack Compose + CameraX）

## 项目信息

- **包名**: `com.banktool.loanphoto`
- **minSdk**: 24 (Android 7.0)
- **targetSdk**: 35 (Android 15)
- **架构**: Clean Architecture + MVVM + Hilt

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 状态管理 | ViewModel + StateFlow |
| 依赖注入 | Hilt |
| 导航 | Navigation Compose |
| 相机 | CameraX (Preview + ImageCapture) |
| 列表 | LazyColumn + Coil |
| Excel | Apache POI (poi-ooxml 5.3.0) |
| 网络 | Retrofit + OkHttp + Moshi |
| GPS | FusedLocationProviderClient + Geocoder |
| 持久化 | Room + DataStore + File (JSON/照片) |
| 存储 | MediaStore + FileProvider + getExternalFilesDir() |
| 后台任务 | WorkManager |
| 水印 | Canvas + Bitmap + Paint + ExifInterface |
| AI | DeepSeek API (deepseek-v4-flash) |
| 日志 | Timber |

## 开发环境

详见 [ENVIRONMENT.md](ENVIRONMENT.md)

## 快速开始

1. 复制 `local.properties.example` 为 `local.properties`，填入 DeepSeek API key
2. 用 Android Studio 打开项目 `E:\loan-photo-app-v4`
3. 等待 Gradle Sync 完成
4. 连接真机或启动 AVD
5. 点击 Run

## 命令行构建

```powershell
# Debug APK
.\gradlew assembleDebug

# Release APK
.\gradlew assembleRelease
```

## 数据兼容性

与既有现场数据格式完全兼容：
- `progress.json` — 拍照进度
- `excel_data_index.json` — Excel 数据索引
- `camera_session.json` — 相机会话
- `visit_notes/` — 走访备注
- `thumbnails/` — 缩略图

## License

内部使用，抚顺银行风险管理部
