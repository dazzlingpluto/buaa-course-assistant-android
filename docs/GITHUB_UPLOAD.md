# 上传到 GitHub

仓库保存安卓 Kotlin 源代码、APK 构建入口、测试和界面截图。APK、工具下载目录、构建缓存、日志及签名私钥由 `.gitignore` 排除。

## 创建本地提交

在项目目录打开 PowerShell。若尚未初始化 Git，先执行：

```powershell
git init -b main
```

检查并提交文件；已有提交时可以跳过：

```powershell
git status --short
git add .
git diff --cached --stat
git diff --cached --name-only
git commit -m "Add Android app and BUAA interface"
git branch -M main
```

提交前确认列表没有账号密码、真实登录 Cookie、私钥或个人数据截图。不要用 `git add -f` 将被忽略的安装包、配置或密钥加入提交。如果 Git 提示未设置作者，请使用你自己的姓名和邮箱配置后重试。

## 上传到新建的空仓库

先在 GitHub 创建一个空仓库，不在网页端预先生成 README、`.gitignore` 或许可证文件。将下面地址中的占位符替换为自己的账户和仓库名，然后执行：

```powershell
git remote add origin "https://github.com/<YOUR_ACCOUNT>/<YOUR_REPOSITORY>.git"
git push -u origin main
```

按 Git 提示完成 GitHub 身份验证。不要将访问令牌写进远端地址、源代码或文档。如果已有 `origin`，先用 `git remote -v` 核对它；无需重复添加，也不要覆盖仍要使用的远端。

## 发布安装包与保留签名

当前版本为 2.3.1（versionCode 24020）。构建后，将已签名的 APK 上传到 GitHub 的正式 Release。2.3.1 对应标签 `v2.3.1`，后续版本请递增版本号和标签。安装包作为 Release 附件分发，源码提交与安装包应对应同一个版本。

手动上传源码时，将解压后的项目文件放到仓库根目录，避免多套一层版本文件夹。把版本化 APK 上传到 Release 附件区。

当前安卓构建使用本地调试签名。请将 `build/android-signing/debug.keystore` 单独备份到安全的本地位置；清理 `build` 前先保留它。后续覆盖安装同一应用需要使用相同的签名。该文件不上传 GitHub，也不作为 Release 附件。公开发行前可另行配置正式签名，本次没有更改签名方案。

本机原签名身份已保留，并为密钥库和私钥分别设置强随机密码。构建脚本自动读取 Windows 当前账户保护的 `signing-passwords.dpapi`；跨设备恢复需要 keystore 与单独保存的新密码。密钥、密码备份和 DPAPI 文件均不上传仓库，源码仅包含读取本地配置的脚本及环境变量名称。

## 当前发布内容

登录页采用 v2.1.0 的蓝白水彩样式，表单顶部直接选择校园直连／校外网络。可选记住密码、自动登录；遇到图形验证码时显示图片与输入框，支持点击刷新和手动重试；不手选本科或研究生，不显示学期日期设置。通过教务门户及实际数据自动选择本科 BYXT 或研究生 GSMIS。

正式课表先显示，再关联 iClass 签到。支持合并课程的多个独立签到场次，同一时段多位教师只计一次操作，分时段分别计数；全部完成显示统一勾号。确认本周无相关 iClass 排课的课程显示无需签到；查询失败或匹配冲突时仍待确认。教务失败不会用 iClass 排课替代正式课表。一键签到已移除。

七天课表适配屏幕宽度，详情隐藏内部教学班及排课编号。签到结果以智慧北航 APP 的“智慧教室”为准。

发布说明应记录本次测试结果。界面预览使用示例数据。学校部分直连签到接口仍使用 HTTP，账号密码、Cookie 和签名密钥不可放入仓库。后续覆盖安装需要保留同一签名。

源码与 APK 应包含各自所需的许可声明。第三方代码来源及许可见 [正式课表说明](ACADEMIC_TIMETABLE.md)。APK 与源码包应对应同一个版本。
