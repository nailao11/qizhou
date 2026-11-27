# 栖舟

<img src="app/src/main/res/drawable-nodpi/ic_terminal.webp" alt="栖舟图标" width="88">

栖舟是 Android 上的中文 SSH 终端与远程文件管理器。支持 Android 8.0 及以上。

## 功能

- **服务器** · 保存多个连接档案，支持 SSH 密码和私钥登录。可用 Android Keystore 加密保存密码及私钥。
- **终端** · 支持主机指纹校验、后台连接、断线重连、中文输入、剪贴板和快捷键。一次打开一个 SSH 终端。
- **文件** · 支持 SFTP、FTP、显式 FTPS 和隐式 FTPS，可浏览目录、新建文件夹、重命名、批量上传、下载和删除。批量删除仅处理文件及空目录。
- **设置** · 可调整终端字号、显示或隐藏快捷键栏、选择文件排序，以及配置新服务器的默认心跳间隔和凭据保存选项。

## 连接

| 协议 | 默认端口 | 用途 |
| --- | --- | --- |
| SSH / SFTP | 22 | 终端和文件管理 |
| FTP | 21 | 明文文件传输 |
| FTPS 显式 | 21 | TLS 文件传输 |
| FTPS 隐式 | 990 | TLS 文件传输 |

首次连接 SSH / SFTP 时，请核对服务器的 SHA-256 指纹。FTPS 需要系统信任且主机名匹配的证书。

直接 SSH 不要求服务器安装额外程序。服务器已装 `tmux` 时，可以为该服务器开启「断线后恢复终端」，重连后回到已有会话。后台联网行为受 Android 及手机厂商省电策略影响。

## 构建

使用 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。可在 Android Studio 中打开项目，或执行：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

调试安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。仓库构建的调试包使用调试签名，覆盖已安装的版本需要使用相同签名。

## 项目资料

- [更新记录](CHANGELOG.md)
- [贡献指南](CONTRIBUTING.md)
- [许可证](LICENSE)
- [第三方许可声明](licenses/)
