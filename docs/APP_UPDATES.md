# 应用更新

## 检查更新

从 2.3.1 开始，App 使用 GitHub Releases 检查更新。首次迁移时请手动覆盖安装 2.3.1。之后，App 进入前台时每 24 小时最多自动检查一次，也可从登录页或课表页顶部点「更新」手动检查。用户无需输入网址或登录 GitHub。

发现新版后可查看发布说明并手动开始下载。App 验证下载大小、SHA-256、包名、版本号和签名，然后由用户在安卓安装界面确认。如果系统要求允许此应用安装未知应用，授权后回到更新页再点击「安装更新」。

默认仓库为 `dazzlingpluto/buaa-course-assistant-android`。应用内更新要求发布仓库和正式 Release 可匿名访问。若需要使用其他公开发布仓库，可在构建前将环境变量 `BUAA_UPDATE_REPOSITORY` 设为对应的 `所有者/仓库名`，再把发布附件上传到那个仓库。已安装指向旧仓库的 v2.3.1 用户，需要从新仓库手动覆盖安装一次。

## 发布新版

1. 在 `android/app/build.gradle` 更新 `versionName` 并递增 `versionCode`，保持应用包名和签名证书一致。
2. 运行 `gradle -p android :app:assembleDebug` 构建，使用 Android Build Tools 的 `apksigner verify` 检查签名。将 APK 命名为 `buaa-course-assistant-v<版本>.apk`。
3. 生成 UTF-8 的 `update.json`，填写与 APK 一致的 `versionCode`、`versionName`、字节大小 `sizeBytes` 和 `sha256`。将 `apkUrl` 设为 APK 文件名，发布说明填入 `notes`。
4. 在 GitHub 创建以 `v<版本>` 为标签的 Release 草稿。将 APK 和 `update.json` 附在同一个 Release，确认两个文件均已上传后，再发布为正式版本并设为 Latest。
5. 在未登录 GitHub 的浏览器打开 `https://github.com/所有者/仓库名/releases/latest/download/update.json`，确认能下载清单。用已支持 GitHub 更新且版本较低的 App 测试检查、下载和覆盖安装。

清单使用同一 Release 内的 APK 文件名。每次发版递增 `versionCode`，相同或更低的版本号不会触发更新。签名材料继续保存在本机。
