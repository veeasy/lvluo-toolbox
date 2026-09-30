# 绿萝工具箱

一个面向 Android 14 的轻量自用浏览器。网页内核使用系统 Android WebView，应用只负责浏览器界面、导航、下载和隐私保护。

## 当前功能

- 地址栏输入网址或直接搜索
- 首页分别提供绿色网址框和蓝色搜索框，避免混淆；网址输入独占一行
- 首页网址框可以独立选择 HTTP 或 HTTPS，默认使用 HTTP
- 网址输入自动把中文冒号、中文句号转换成英文标点
- Bing 中国与百度搜索引擎，可在菜单中切换
- 前进、后退、主页、刷新和停止加载
- 本地收藏夹；页面底部可以快速收藏或取消收藏
- 本地浏览历史，最多保留最近访问的 500 个不同网址，并显示网站图标
- 竖屏相机扫描二维码和常见条形码；不依赖 Google 服务
- 首页工具区块和更多菜单均可进入扫码；收藏夹、浏览历史、扫码菜单带图标
- 普通资源请求及 Service Worker 请求的域名级广告拦截
- 页面广告元素隐藏和实时拦截计数
- 第三方 Cookie 默认关闭
- SSL 错误和 Safe Browsing 风险默认阻止
- 文件上传、系统下载管理器、分享和复制链接
- 桌面版网页切换
- 清除 Cookie、缓存、表单和网页存储
- 注册为 Android 默认浏览器候选应用

## 构建

项目使用 JDK 17、Android Gradle Plugin 8.12.0 和 Gradle 8.13。SDK 路径写在本机的 `local.properties` 中。

```powershell
.\gradlew.bat assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

连接开启 USB 调试的手机后安装：

```powershell
D:\softdir\Android\SDK\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## 广告规则

首版使用可审计的小型内置域名列表：

```text
app/src/main/assets/blocked_domains.txt
```

每行写一个域名，其所有子域名也会被拦截。主页面请求不会被域名规则拦截，避免误伤用户主动访问的网站。

## 已知限制

- 这是 MVP，目前是单标签页。
- 内置规则不是完整 EasyList 语法，只处理域名和少量通用 CSS 选择器。
- 摄像头、麦克风和定位权限默认拒绝；后续可以添加逐网站授权界面。
- 密码管理和过滤规则自动更新尚未实现。
