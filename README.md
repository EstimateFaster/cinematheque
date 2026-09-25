# 影厅 · Cinematheque

把一台 Android 手机变成**本地影视中心**的原生 App：**自研 UI** 的海报墙 + 原生硬解全屏播放器。

面向设备：荣耀 20（YAL-AL10，麒麟 980，Android 10 / EMUI 10.1），非 root、纯本地片源。

---

## 它是什么

| 层 | 实现 | 说明 |
|---|---|---|
| 界面 | **自研 WebView UI**（HTML/CSS/JS，无任何前端框架） | 影院暗场基调 + 环境光溢出 + 玻璃拟态控制层 |
| 数据 | 原生 `MediaStore` 扫描 | 读出手机全部本地视频，按文件夹自动分类 |
| 缩略图 | 原生 `MediaMetadataRetriever` 抽帧 | 后台线程 + 内存 LRU + 磁盘缓存，输出 data-URI |
| 播放 | 原生 `MediaPlayer` + `SurfaceView` | 硬解、可 seek、倍速、全屏沉浸 |
| 桥接 | `addJavascriptInterface` | UI 与原生双向调用 |

### 为什么是「原生壳 + 自研 WebView UI」

纯原生布局做不出海报墙需要的视差、渐变、环境光质感；纯 WebView 播放又依赖系统解码、seek 不稳定。
所以界面交给自研 WebView（完全可控的视觉），播放交给原生 `MediaPlayer`（硬解稳定）。

### 功能

- **Hero 大图轮播**：最近加入的影片，自动切换、随片变色环境光
- **继续观看**：记录每部片子的播放位置，跨重启保留（原生 `ProgressStore`）
- **按文件夹自动分类栏**：横向滑动，最多 6 个来源文件夹
- **全部影片网格** + 搜索（片名/文件夹）+ 四种排序（时间/名称/大小/时长）
- **收藏**：收藏项自动置顶
- **全屏播放器**：自定义控制层 —— 拖动进度条、±10 秒、倍速 1.0/1.25/1.5/2.0/0.75、静音、旋转、缓冲显示
- **手势**：单击切控制层、双击左右侧快退/快进 10 秒、双击中间暂停
- **全屏沉浸**：隐藏状态栏与导航栏，`IMMERSIVE_STICKY` + 常亮

---

## 安装

从 [Actions](../../actions) 下载最新构建产物 `cinematheque-apk`，或从 [Releases](../../releases) 下载 APK：

```bash
adb install -r cinematheque.apk
```

首次启动会请求存储权限，点「允许」后自动扫描本地视频。

---

## 构建

**不在本地构建**，统一走 GitHub Actions：推送到 `main` 即自动构建，或手动触发 `workflow_dispatch`。
产物为仓库 Artifact；创建 Release 时会自动附带 APK。

工作流：[`.github/workflows/build.yml`](.github/workflows/build.yml)

如需本地构建（脚本本身是可移植的，会自动下载 SDK 并生成签名密钥）：

```bash
bash tools/build.sh
# 产物: dist/cinematheque.apk
```

可配置环境变量：`ANDROID_SDK`、`OUTPUT_DIR`、`KS_PATH`、`KS_PASS`。

---

## ⚠️ 硬约束：不能有匿名内部类

本项目的构建工具链是 `aapt2 + javac + d8`（不用 Gradle）。**部分 d8 版本（如 34.0.0 的 R8 8.2.2-dev）在编译带隐式 `this$0` 的类时会崩溃**：

```
java.lang.NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
```

已实测会被击穿的写法：

- 匿名内部类：`new Runnable(){...}`、`new LruCache<...>(n){...}`
- 非静态内部类：`final class StopNow implements Runnable`（在 Activity 内）
- 方法引用 / lambda 生成的合成类同样有风险

**因此源码里所有回调、监听器、线程体一律写成 `static final class`，并显式持有外部引用**：

```java
// ✗ 会崩
web.postDelayed(new Runnable(){ public void run(){ scan(); } }, 1000);

// ✓ 安全
static final class Refresh implements Runnable {
    private final MainActivity act;
    Refresh(MainActivity a){ this.act = a; }
    @Override public void run(){ act.scan(); }
}
web.postDelayed(new Refresh(this), 1000);
```

改代码时请保持这个约定。

---

## 目录结构

```
.github/workflows/build.yml   CI：构建 + 产物 + Release 附件
tools/
  build.sh                    可移植构建脚本
  AndroidManifest.xml
  res/values/strings.xml
  assets/                     ← 自研 UI（纯手写，无框架）
    index.html                结构：Hero / 分类栏 / 网格 / 详情层 / 播放控制层
    styles.css                视觉：暗场基调、环境光、玻璃拟态、横竖屏适配
    app.js                    逻辑：数据渲染、搜索排序、进度、播放器控制协议
  java/com/dsh/mediacenter/
    MainActivity.java         WebView 宿主 + MediaStore 扫描 + JS 桥
    PlayerActivity.java       原生全屏播放器 + 手势
    Thumbs.java               缩略图抽帧（线程 + LRU + 磁盘缓存）
    MediaProvider.java        把 video id 映射为可 seek 的 fd
    ProgressStore.java        观看进度持久化
```

---

## UI ↔ 原生 接口

前端通过 `window.Native` 调用：

| 方法 | 作用 |
|---|---|
| `listVideos()` | 返回媒体库 JSON |
| `thumb(id)` | 取缩略图 data-URI（未命中返回空串，后台生成后重试） |
| `progress()` | 取全部观看进度 |
| `play(id, startMs)` | 全屏播放 |
| `playerState()` | 播放器状态 JSON（pos/dur/buf/playing/speed/muted） |
| `toggle() / seekBy(ms) / seekTo(frac) / mute() / cycleSpeed() / rotate() / stop()` | 播放控制 |
| `rescan()` | 重新扫描媒体库 |

原生回调前端：`MC.onLibraryChanged()`、`MC.onProgress()`、`MC.onPlayerClosed()`。

---

## 已知限制

- 片源必须**在手机本地**（非 root 无法挂载 SMB/NFS）
- 播放器走 `MediaPlayer`，**不支持的编码**（部分 HEVC 10bit / AV1 / DTS 音轨）会提示无法播放
- 未接在线刮削，标题取文件名；无海报元数据时用视频抽帧作封面
- 仅针对 Android 10（API 29）验证，其他版本未测
