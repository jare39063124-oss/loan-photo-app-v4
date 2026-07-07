# 开发环境记录

> 此文件记录所有开发环境安装位置，避免重复安装。
> 最后更新：2026-07-08（迁移完成 + Gradle wrapper 缓存修复）

## JDK

| 项 | 值 |
|---|---|
| 版本 | Java 17.0.12 LTS (HotSpot 64-Bit Server) |
| 路径 | `C:\Program Files\Java\jdk-17` |
| 来源 | 用户机器已安装（Phase 0 前已存在） |

## Android Studio

| 项 | 值 |
|---|---|
| 安装路径 | `D:\AndroidStudio`（待用户手动安装） |
| 下载地址 | https://developer.android.com/studio |
| 备注 | GUI 安装程序，需用户手动运行。安装时选择路径 `D:\AndroidStudio` |

## Android SDK

| 项 | 值 |
|---|---|
| ANDROID_HOME | `D:\AndroidSdk` |
| SDK Platform | android-35 (Android 15) |
| Build-Tools | 35.0.0 |
| NDK | 28c (待安装) |
| Platform-Tools | 已安装 |
| CMDLine-Tools | `D:\AndroidSdk\cmdline-tools\latest` |
| 系统环境变量 | ANDROID_HOME=D:\AndroidSdk（待设置） |

## Gradle

| 项 | 值 |
|---|---|
| 版本 | 8.10.2 |
| 本地安装路径 | `D:\gradle-8.10.2` |
| Wrapper 缓存 | `C:\Users\Administrator\.gradle\wrapper\dists\gradle-8.10.2-bin\a04bxjujx95o3nb99gddekhwo\` |
| Wrapper 状态 | 已用本地安装填充（zip + .ok 标记 + 解压目录），`./gradlew` 可离线使用 |
| 验证 | `./gradlew assembleDebug` BUILD SUCCESSFUL in 7s（2026-07-08） |

## AVD（模拟器）

| 项 | 值 |
|---|---|
| 设备 | Pixel 7（待用户创建） |
| API | 35 |
| 架构 | arm64-v8a |
| 备注 | 通过 Android Studio → Device Manager 创建 |

## 项目信息

| 项 | 值 |
|---|---|
| 项目路径 | `E:\loan-photo-app-v4`（纯英文路径，避免 AGP 非 ASCII 路径报错） |
| 包名 | `com.banktool.loanphoto` |
| minSdk | 26 (Android 8.0) — Apache POI 5.x 要求 API 26+（MethodHandle），所有目标机型均 API 30+ |
| targetSdk | 35 (Android 15) |
| compileSdk | 35 |
| Java 版本 | 17 |
| Kotlin 版本 | 2.0.21 |
| AGP 版本 | 8.7.3 |
| Compose BOM | 2024.12.01 |

## GitHub 仓库

| 项 | 值 |
|---|---|
| 仓库名 | loan-photo-app-v4 |
| 与 v3 仓库关系 | 完全独立（v3 = `jare39063124-oss/loan-photo-app`） |
| 创建方式 | 待用户通过 GitHub Web 或 gh CLI 创建 |

## 目标机型

| 机型 | 系统 | 用途 |
|---|---|---|
| 华为 Mate 70 | HarmonyOS 4.3 | 真机测试 |
| OPPO Reno11 Pro | ColorOS 14 | 真机测试 |
| 小米 13 Pro | HyperOS | 真机测试 |

## 待用户手动完成的事项

1. **安装 Android Studio GUI** — 下载 https://developer.android.com/studio，安装到 `D:\AndroidStudio`
2. **设置系统环境变量** — `ANDROID_HOME=D:\AndroidSdk`，PATH 追加 `%ANDROID_HOME%\platform-tools`
3. **创建 GitHub 仓库** — 新建 `loan-photo-app-v4`（空仓库，不勾选 README/.gitignore/license）
4. **创建 AVD** — Android Studio → Device Manager → Pixel 7 / API 35 / arm64-v8a

## DeepSeek API Key 安全存储

- 存储位置：`local.properties`（已在 .gitignore 中）
- 注入方式：`build.gradle.kts` 通过 `buildConfigField` 读取 `DEEPSEEK_API_KEY` 属性注入 `BuildConfig.DEEPSEEK_API_KEY`
- 验证命令：`git log -p | findstr "sk-"` 应无匹配
- 模板文件：`local.properties.example`（已提交到 git，不含真实 key）
