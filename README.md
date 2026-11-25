# AudioHub

Windows / Android 局域网和软件蓝牙音频房间，v0.9.6 开发预览。作者标识：**A-creator51**。

源码可见，仅允许个人非商业用途；**不是OSI意义的开源项目**。使用和分发前请阅读 [使用说明](使用说明.md)、[LICENSE](LICENSE)、[免责声明](DISCLAIMER.md) 和 [第三方声明](THIRD_PARTY_NOTICES.md)。不提供商用或机构使用授权。

## 功能与边界

- 受支持设备可创建或加入房间，也可独立作为输入/输出；单房间仍有一个中心，退出不自动迁移。
- 局域网TCP/TLS混音分发，软件蓝牙RFCOMM/TLS/IMA1。最多5入5出是软件限额，**不保证蓝牙满载或严格同步**。
- Windows直接连接（A2DP）发送端不需App，可系统直出或端点回采后多路输出。Android无公开通用A2DP接收能力，相关操作禁用。
- 总览、分模块页面、大日志、输入/输出独立开关、实时音量范围和直接端点回绕保护。
- 不启动即采集，不保存房间码或音频；日志仍可能包含IP和设备名。真实无线音频、长期稳定、锁屏和耳机共存需真机验收。

## 运行

Windows交付在 `dist/Windows/AudioHub-0.9.6/`，必须保留完整目录；**公开版不附带JVM**。另装64位Java21并设置 `JAVA_HOME`，系统需WebView2 Runtime。Android10+使用 `dist/Android/` 中APK。实际下载应从仓库的Releases获取，不提交dist到Git。

新发布签名与旧开发APK不同，旧开发版通常不能直接覆盖升级；不要为安装而无备份卸载。签名证书公开名称为A-creator51，自签名不代表商业CA认证。EXE尚无受信任代码签名。

## 构建与测试

安装Git、JDK21、Node20+、Gradle8.13、Android SDK35（Build Tools35.0.0），Windows构建另需x64 MinGW-w64 GCC13+。工具链按各自条款安装，不作为本项目源码提交。

复制 `.env.example` 为本机 `.env` 并填写工具路径；`.env`不执行命令，只解析 `KEY=value`。不要把密码填入example。也可用同名进程环境变量，优先于.env。

```powershell
.\scripts\test-room.ps1
npm ci --ignore-scripts
npx playwright install chromium
.\scripts\test-ui.ps1
.\scripts\build-windows.ps1
.\scripts\test-desktop.ps1
.\scripts\build-android.ps1
# 发布APK需先配置私有签名，再执行：
.\scripts\build-android.ps1 -Release
```

Android默认使用官方仓库；需要镜像时仅在本机.env设置 `AUDIOHUB_USE_MIRROR=true`。依赖已缓存可加 `-Offline`，首次下载不能保证离线构建。Gradle版本固定为8.13，由使用者安装并配置GRADLE_HOME/PATH；没有把工具链私有路径硬编码进源码。

首次创建作者自己的签名可运行 `scripts/init-signing.ps1`：它只在.env和签名库都不存在时工作，生成RSA4096/SHA256、48随机字节密码，存入忽略的 `.env` 和 `.signing/`。**不要重新生成本项目作者密钥来给他人冒用身份**；fork应使用自己的证书身份。私钥和密码必须分别离线备份，丢失后无法继续以同一身份升级。详见 [发布与隐私检查](docs/发布与隐私检查.md)。

## 目录

```text
src/shared/    一份共用Java引擎
src/ui/        一份共用离线界面
src/windows/   Windows启动器和平台实现
src/android/   Android工程
tests/         合成音频、协议和UI回归
scripts/       可移植构建、私有签名、隐私检查与导出
docs/          使用说明、经验、改动与项目介绍
third_party/   少量必要依赖及原始许可
.github/       只读测试流程与问题模板
```

不上传旧版本目录、真实日志、设备身份、密钥、构建缓存或本机历史记录。该仓库未附Git提交历史和作者本机配置。发布前运行隐私检查并使用导出脚本；`.gitignore`不等于加密，不要强制添加被忽略文件。已完成的检查和未覆盖范围见 [发布验证记录](docs/发布验证记录.md)。
