# 安装指南（KPW3 · 安卓 4.4.2）

## 总览：先走哪条路

```
想最快用上？ ──→ 路线 A：装 KOReader + weread.koplugin（零编译，15 分钟）
想要独立 App？ ──→ 路线 B：本工程 APK
                    ├─ B1 云端构建（推荐，本机零环境）
                    ├─ B2 本机 Android Studio 构建
                    └─ B3 传输到 KPW3 并安装
```

---

## 路线 A：KOReader + 微信读书插件（先试这个）

1. **装 KOReader**
   到 https://koreader.koplugin.xyz/downloads 下载 Android 版 APK，传到 KPW3 安装。
   ⚠️ 插件要求 KOReader 2026.03+；新版对安卓 4.4.2 的兼容性需实测，
   若打不开或闪退，直接转路线 B。

2. **装插件**
   到 https://github.com/finlater/weread.koplugin/releases 下载最新
   `weread.koplugin-vX.Y.Z.zip`，解压得到 `weread.koplugin` 文件夹，
   放到 KOReader 数据目录的 `plugins/` 下（即 `koreader/plugins/weread.koplugin/`）。

3. **登录**
   手机微信读书 App → 我 → 设置 → 微信读书 Skill → 获取 API Key（记下来）。
   KOReader 里：工具 → 微信读书 → 微信扫码登录 → 手机扫码确认（有 4 位码就输入）。

4. 菜单里出现书架，直接开读。完成。

---

## 路线 B1：云端构建 APK（本机零环境，约 10 分钟）

1. 注册/登录 GitHub，新建一个仓库（Private 即可）。
2. 把 `eink-weread/` 文件夹的**全部内容**上传为仓库根目录
   （注意：`.github/` 文件夹必须在仓库根目录，工程已带好 `build.yml`）。
3. 打开仓库 → Actions 标签 → 等待 "Build APK" 工作流跑完（约 3~5 分钟）。
4. 在该次运行的 Artifacts 里下载 `eink-weread-debug-apk`，解压得到 `app-debug.apk`。

> 之后每次改完代码 push 上去，都会自动出新 APK。

## 路线 B2：本机 Android Studio 构建（有开发环境时）

1. 安装 Android Studio（自带 SDK，一路默认）。
2. 打开 `eink-weread/` 目录作为工程，等待 Gradle 同步完成。
3. 菜单 Build → Build Bundle(s)/APK(s) → Build APK(s)。
4. 产物在 `app/build/outputs/apk/debug/app-debug.apk`。

命令行方式：`cd eink-weread && gradle assembleDebug`（需 Gradle 6.7+、JDK 8/11）。

---

## 路线 B3：把 APK 装进 KPW3

### 传文件（任选其一）

- **USB**：KPW3 连电脑（刷机 ROM 一般会挂载为 MTP/U 盘），把 `app-debug.apk`
  拷到设备存储根目录或 Download。
- **adb**（ROM 开了 USB 调试时最直接）：
  ```bash
  adb install -r app-debug.apk
  ```
  无线方式：设备与电脑同一 WiFi，先 `adb connect <设备IP>:5555` 再 install。

### 安装

1. KPW3 上打开**设置 → 安全**，勾选"允许安装未知来源应用"（刷机 ROM 的位置可能略有差异）。
2. 用文件管理器（ROM 自带；没有就先装一个"X-plore"之类的 APK）找到 `app-debug.apk` 点安装。
3. 桌面出现「微信读书」图标。

### 首次启动

1. 打开 App → 自动显示登录二维码 → 手机微信扫码确认（需要输 4 位验证码就输入）。
2. 回到书架 → 按**菜单键 → 设置 API Key** → 粘贴
   微信读书 App 里"微信读书 Skill → 获取 API Key"的密钥。
3. 点一本书进入阅读：
   - 音量键 / 点按翻页（左 1/3 上一页）
   - **长按文字** → 选中段落 → 划线 / 写想法
   - **菜单键** → 选章 / 本章划线 / 本章想法 / 书评 / 发表想法 / 时长上报开关
4. 建议：打开书后按菜单键开启「时长上报」，阅读时长和进度就会同步到手机端。

---

## 常见问题

| 现象 | 处理 |
|------|------|
| 安装时提示"解析包错误" | APK 传输损坏，重新拷贝；或确认下载的是 debug APK 原文件（没有解压损坏） |
| 打开就闪退 | 4.4.2 ROM 缺组件，用 `adb logcat` 看 crash 日志发我 |
| 二维码一直不刷新 | 检查设备网络；微信读书网页登录接口偶发风控，等几分钟重试 |
| 书架加载失败提示 API Key | 菜单键重新粘贴；确认在微信读书 App 里已开通"微信读书 Skill" |
| 章节加载失败 | 协议可能随官方改版，参照 finlater/weread.koplugin 最新实现更新 `WeRead.java` |
| 划线后位置不对 | 看 Toast 提示的"服务器 range vs 本地 range"差值，按 README"坐标校准"一节调整 |
| 上报失败 | 自动续期后重试一次；持续失败则退出 App 重新扫码登录 |
