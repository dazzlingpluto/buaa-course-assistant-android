# BUAA Course Assistant · 北航课程助手

面向 Android 手机的北航课程助手，提供课表查询、课程状态查看和手动签到功能。应用以独立 APK 运行，手机无需安装 Python，也无需电脑保持开机。

## 功能与使用

当前版本 **2.3.1**（versionCode 24020）。在 [GitHub Releases](https://github.com/dazzlingpluto/buaa-course-assistant-android/releases/latest) 下载 `buaa-course-assistant-v2.3.1.apk`，可覆盖安装使用相同证书签名的旧版。应用内「更新」通过 GitHub Releases 检查正式版本，发布步骤见[应用更新指南](docs/APP_UPDATES.md)。

- 恢复 v2.1.0 水彩头图和白色登录卡片，表单顶部直接选择校园直连／校外网络。
- 输入统一认证学号与密码；按需勾选记住密码、自动登录。登录页不显示身份选择或学期基准日。
- 根据教务门户和实际课表响应自动选择本科 BYXT 或研究生 GSMIS。
- 在线周课表、学期选择、本周定位、学校返回的完整周次与节次、课程详情；未安排时间的课程单独列出。
- 正式课表先显示，再关联 iClass 签到。支持一门正式课程对应多个不重叠的 iClass 签到场次，各场独立操作并显示已签进度；确认本周无任何相关 iClass 排课的课程显示“✓ 无需签到”；加载失败或存在匹配冲突仍待确认；签到服务失败不阻止课表显示。一键签到已移除。

记住密码与自动登录默认关闭，开启自动登录会同时启用记住密码；成功登录后保存加密凭据，下次启动仅尝试一次，失败可以手动重试。退出登录会关闭已保存的自动登录；取消记住密码会删除凭据。旧记录可以读取，但其中手选的学生身份不再用于路由。

校园 Wi-Fi 选择校园直连；校外选择校外网络。2026–2027 秋季学期默认起点为 2026-09-07；本科使用学校周次日期，研究生使用移植代码校验的课表校历，缺少证据时仅该学期使用此默认值。学期与周次由学校接口返回。

正式课表失败会显示错误，不再用 iClass 课程冒充教务课表。刷新失败保留的旧课程只供查看，重新加载成功后恢复签到关联。

遇到统一认证图片验证码时，登录页会显示图片与输入框；手动输入后继续登录，可点击图片刷新。自动登录遇到验证码会暂停，不循环提交。验证码不保存到记住密码记录。

本版尚未使用真实学校账号或手机验证，自动测试不代表实际学校接口已验证。

## 界面预览

以下为 Compose 在 Robolectric 中渲染的演示数据截图，不是真机截图。

| 登录页 | 周课表 | 课程详情 |
| --- | --- | --- |
| ![原生 BUAA 登录页](docs/native-login.png) | ![蓝白周课表](docs/native-courses.png) | ![课程详情](docs/native-course-detail.png) |

验证码登录演示见 [验证码预览](docs/native-login-captcha.png)。

界面改版历史见 [课表改版说明](docs/TIMETABLE_UI.md)。

## 运行原理

`MainActivity.kt` 加载 Compose 界面；`AssistantViewModel.kt` 管理登录、课表和操作状态；`IclassRepository.kt` 使用 OkHttp 完成学校认证、BYXT／GSMIS 正式课表和 iClass 签到请求；`AcademicTimetableProtocol.kt` 解析正式排课，`AcademicScheduleMapper.kt` 生成周课表并关联签到 ID，`GsmisProtocol.kt` 提供 GSMIS 课程身份与学期解析。应用不再加载 WebView，也不打包 Python 运行时。

界面素材随 APK 本地打包，不需要图片服务器。应用仍需要联网访问学校认证与课程服务。

## 构建 APK

构建环境：Gradle 9.6、Android Gradle Plugin 9.4、Kotlin / Compose 编译器 2.2.90、完整 JDK 17 或更高版本（本机使用 JDK 21），以及 Android SDK 36 / Build Tools 36.0.0。构建不需要 Python 或 Android NDK。

在 Windows 上设置 JDK 和 Android SDK 路径，并为此项目准备本地调试签名后，在仓库根目录运行：

```powershell
gradle -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 位于 `android/app/build/outputs/apk/debug/`。调试签名使用 `build/android-signing/debug.keystore`，密钥别名为 `androiddebugkey`。签名密码可通过 `BUAA_SIGNING_STORE_PASSWORD` 和 `BUAA_SIGNING_KEY_PASSWORD` 提供。覆盖安装需要与现有版本相同的签名证书。

构建与离线测试不能代替实际学校接口和设备验证。新版本尚未进行真实账号和手机验证，首次使用时请核对学生身份、所选学期、课程时间和签到关联。

版本演进与兼容说明见 [原生重写说明](docs/NATIVE_REWRITE.md)。登录、课表、签到及验证码的测试位于 `android/app/src/test/`。

## 上传与发布

仓库保存安卓源码、构建入口、测试、界面素材和演示截图。APK、签名密钥、日志与构建缓存已设置忽略。上传仓库、发布 APK 和保留签名的步骤见 [GitHub 上传指南](docs/GITHUB_UPLOAD.md)。

## 使用与隐私说明

- 原生版 HTTPS 请求启用证书与主机名校验。学校部分直连签到接口仍使用 HTTP，相关传输仍存在风险；本机加密不能消除这些风险。
- “记住密码”和“自动登录”默认关闭，仅在成功登录后保存最近使用的一组账号、密码、网络模式、学生身份和自动登录选择。凭据使用 Android Keystore 密钥加密，密文存放在应用的非备份私有目录，不写入网页存储或日志；运行会话仅保存在内存中。卸载应用或清除应用数据会删除保存的信息。
- 不要将学校账号、密码、真实登录 Cookie 或个人数据截图加入仓库。
