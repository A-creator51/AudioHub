# Third-party notices / 依赖许可审核

本项目原创部分采用个人非商业许可；以下第三方材料**不适用**该限制。不得删改第三方版权/许可，不能把整个压缩包笼统声称为“禁止商用的所有内容”。这是针对本地实际文件的工程核对，不是律师意见或对未知代码来源的权属保证。

| 项目/使用方式 | 核实条款与对原创源码的影响 | 处理 |
|---|---|---|
| Microsoft.Web.WebView2 1.0.3650.58，头文件/Loader DLL | SDK附带BSD风格许可，保留声明/免责/不背书条件；未要求独立应用改GPL | third_party/webview2保留原LICENSE/NOTICE/nuspec；Windows包复制声明 |
| GCC13 libgcc/libstdc++，编译时链接 | GPLv3加GCC Runtime Library Exception3.1，合格编译过程允许独立模块用自选条款发布 | 保留COPYING3/COPYING.RUNTIME；不用GCC源码作本项目原创代码 |
| MinGW-w64运行库与winpthreads | 实际随工具链的BSD/MIT等宽松声明要求保留；不要求独立应用开放商用 | 原始声明在third_party/licenses；Windows包复制 |
| Java/OpenJDK运行时与标准库接口 | 本机Zulu/OpenJDK许可含GPLv2/Classpath Exception，独立应用可不同许可；分发JVM本体仍有许可/源代码义务 | **本公开版不分发JVM**，用户另装Java21；不把GPL组件改为非商业 |
| Gradle8.13/AGP8.9.2及构建依赖 | 工具/插件主要Apache-2.0等；作为构建工具不使原创应用自动采用其许可 | 不捆绑工具链；按官方下载与其条款使用 |
| Playwright1.62.1、Node、测试浏览器 | Playwright Apache-2.0；测试工具不链接进交付应用，浏览器保持各自许可 | npm devDependency；不打包node_modules或浏览器 |
| Windows/Android系统API、WebView2 Runtime | 运行平台按其系统/SDK条款使用；不是本项目原创代码 | 不捆绑Windows/Android系统或WebView2 Runtime |

实际使用的是MinGW编译器而非Qt框架：没有链接Qt库，不能因为编译器安装在某工具集下就推断本项目采用Qt许可。发布前仍须核对实际二进制导入与未来新增依赖。

参考：

- https://www.gnu.org/licenses/gcc-exception-3.1.html
- https://openjdk.org/legal/gplv2+ce.html
- https://www.mingw-w64.org/license/
- https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.3650.58
- https://github.com/microsoft/playwright/blob/main/LICENSE
- https://github.com/gradle/gradle/blob/master/LICENSE

许可结论：已核实的当前集成方式未发现必须把**原创应用源码**改为GPL或允许商用的要求，因此已为原创部分写入个人非商业许可。它不能限制第三方原有权利。旧实现及任何外部代码的完整作者/来源，仍应由权利人确认；将来新增GPL/AGPL等依赖必须重新审核，不沿用本结论。

Windows包内还有GCC异常、MinGW-w64和winpthreads的全文，以及WebView2 LICENSE/NOTICE。本仓库不包含用于发布的私钥、Java运行库或完整SDK/浏览器二进制。
