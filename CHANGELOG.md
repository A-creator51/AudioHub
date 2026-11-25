# Changes

## 0.9.6 public-source packaging

- GitHub结构：单份src/shared、src/ui及Windows/Android实现，tests/scripts/docs分离。
- 隐私优先：不复制旧缓存、身份、日志或截图；本机路径和签名密码仅进忽略的.env，私钥进忽略的.signing。增加候选文件扫描与白名单导出ZIP。
- 构建从JAVA_HOME、GRADLE_HOME、ANDROID_HOME、MINGW_CXX或PATH定位；UI测试用npm依赖/可选本机浏览器。第三方镜像改为本机显式选择。
- 原创代码采用个人非商业许可，第三方保持原许可并附全文。公开Windows包不附JVM，改用用户安装x64 Java21；不承诺原免安装Java行为。
- Android发布签名身份A-creator51，RSA4096/SHA256，随机384位密码材料；不修改旧版本密钥，不把.env/private key上传。
- 新增免责声明、隐私发布指南、安全政策、问题模板与无签名的只读测试CI。APK新签名不能覆盖旧开发签名APK，版本号仍96，仅作预览发布。

## 0.9.6 functionality

- 直接连接框新增独立回采设备和开启/关闭/刷新，关闭保留连接与播放；刷新端点不影响A2DP来源。
- 明确WIN直出/回采多路输出、同端点回绕保护与系统原声不静音。
- 音量范围选择实时限制真实增益并单独保存，聚焦推子同步上限，提高范围不自动增音量，不误保存其他草稿。

保留0.9.5直接连接入口/模式互斥、0.9.4软件蓝牙与无边框全屏、0.9.3输入输出独立和节奏修复。真实无线音频/满载尚需验收，详见README和免责声明。
