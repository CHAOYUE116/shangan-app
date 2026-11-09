# 上岸 · Android 壳

「上岸」的公考刷题 App 的 Android 外壳。用 Capacitor 把 `../kaogong-shuati/public` 里的网页包进 WebView，再补三样网页做不到的事：本地 SQLite、题库增量更新、普通下载干不动的长连接下载。

题目内容、界面、交互都在 `../kaogong-shuati` 那个仓库里。这个仓库只有壳。

## 这个壳负责什么

| 能力 | 位置 | 为什么必须在原生层 |
| --- | --- | --- |
| 打开本地题库 | `NativeDbBridge.java` | 317 MB 的 SQLite，浏览器里塞不下也查不动 |
| 应用题库增量包 | `NativeDbBridge.applyDelta()` | 要在一个事务里 upsert 十几万行 |
| 检查 / 下载更新 | `UpdateManager.java` | 要绕过 WebView 的跨域限制，还要断点续传 |
| 下载不被系统杀掉 | `DownloadService.java` | 前台服务 + 常驻通知 |
| 安装新版本 APK | `UpdateManager.installApk()` | `FileProvider` + `ACTION_VIEW` |
| 启动图 | `SplashBridge.java` | 网页出来之前先把品牌图铺上，避免白屏 |
| 网页与原生之间的桥 | `WebBridge.java` `MainActivity.java` | `addJavascriptInterface` |

## 目录

```
android/
  app/src/main/
    java/com/kaogong/app/
      MainActivity.java        入口，注册各个 JS 桥
      WebViewActivity.java     内嵌浏览器（带标题栏和进度条）
      WebBridge.java           业务桥：刷题记录、错题、笔记、备份
      NativeDbBridge.java      题库读写 + 增量包应用
      UpdateManager.java       检查/下载/校验/安装
      DownloadService.java     下载用的前台服务
      SplashBridge.java        启动图覆盖层
    res/
      values/styles.xml        主题（注意：不用 Theme.SplashScreen）
      values/colors.xml        boot_bg = #F5F6F8，和首页同色
      drawable*/splash.png     启动图，11 个 density，由 ic_launcher 生成
    AndroidManifest.xml
  build.gradle
  variables.gradle             minSdk 22 / compile 34 / target 34
capacitor.config.json
```

## 构建

前置：JDK 17、Android SDK 34。

```bash
# 1. 把网页同步进来（源在另一个仓库）
node ../kaogong-shuati/tools/sync-webapp.mjs
robocopy ../webapp android/app/src/main/assets/public /MIR

# 2. 打包
cd android
set JAVA_HOME=D:\dev\jdk17
gradlew.bat assembleRelease
```

产物在 `android/app/build/outputs/apk/release/app-release.apk`。

## 三个没入库的东西

这三样是本机资产，`.gitignore` 里都排除了：

1. **`android/kaogong-release.keystore` + `android/keystore-pass.txt`**
   签名密钥和口令。**绝对不能进仓库**：这仓库是公开的，泄漏了别人就能签一个同签名的「更新包」，而 Android 只认签名不认来源，用户机器会自动接受。换机器时手动拷过去。

2. **`android/app/src/main/assets/public/`**
   网页包 + 两个数据库（`tiku_app.db` 317 MB、`images.db` 72 MB）。每次构建前从 `../kaogong-shuati/public` 同步，是产物不是源码。用上面的 `robocopy` 一条命令就能重建。

3. **`android/local.properties`**
   记本机 SDK 路径，`sdk.dir=D:\\dev\\android-sdk`。

## 几个踩过的坑

**启动图不能用 `Theme.SplashScreen`。**
系统的 splash 主题在退出时会播自己的动画，和自建的覆盖层打架，顶栏会留一片图标残影。现在的做法：主题设成普通 `NoActionBar` + `windowBackground=@color/boot_bg`（纯色），真正那张图由 `SplashBridge` 在 WebView 加载完时铺上，前端首屏渲染好再调 `AndroidSplash.hide()` 淡出。三段颜色一致，看不出接缝。
另外**不要**把 png 设成 `windowBackground` —— bitmap drawable 会被 density 缩放，同样出残影。

**下载必须有前台服务。**
更新包 80~135 MB，公共反代几十到几百 KB/s，要跑十几分钟。没有前台身份的话用户一按 HOME，系统就把进程当后台任务回收，下载线程跟着死。`DownloadService` 声明了 `foregroundServiceType="dataSync"` 和 `stopWithTask="false"`。

**分块下载要算好块大小。**
每个块都要重新走一遍代理的 302 和 TLS 握手，块切太碎（试过 2 MB）反而比 4 MB 慢。现在 16 线程 × 4 MB，实测比单流快 4~8 倍（单流 0.36 MB/s → 并发 2.9 MB/s）。临时文件名里带着总大小，换版本不会误用旧块。

**增量包要连分类索引一起发。**
`question_categories` 不在源库 `tiku.db` 里，早期增量包只发 `papers` / `questions`，结果新题「搜得到但进不了模块树」，题库页题数一直不动。现在包里带全量分类表，应用前先 `DELETE` 再整表灌。

**diff 要用主键 `id`，不能用 `questionId`。**
`questionId` 不唯一（10.5 万行只对应 6.5 万个 questionId），用它做 Map 的行键会把重复行吃掉，永远补不齐。

## 版本号

`android/app/build.gradle` 里 `versionCode` / `versionName`，由 `../kaogong-shuati/tools/bump-version.mjs` 统一改（那个脚本同时改前端 `APP_VERSION`）。
App 版本（`0.1.x`）和题库版本（`0.5.x-日期-时刻`）是两套独立的号，不要混。
