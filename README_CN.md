# AutoPhone

<div align="center">

**基于 Open-AutoGLM 的 Android 原生手机智能助手应用**

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple.svg)](https://kotlinlang.org)

中文 | [English](README.md)

</div>

---

## 📖 项目简介

AutoPhone 是基于 [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) 开源项目二次开发的 Android 原生应用。它将原本需要电脑 + ADB 连接的手机自动化方案，转变为一个独立运行在手机上的 App，让用户可以直接在手机上使用自然语言控制手机完成各种任务。

**核心特点：**
- 🚀 **无需电脑**：直接在手机上运行，无需 ADB 连接
- 🎯 **自然语言控制**：用自然语言描述任务，AI 自动执行
- 🔒 **Shizuku 权限**：通过 Shizuku 获取必要的系统权限
- 🪟 **悬浮窗交互**：悬浮窗实时显示任务执行进度
- 📱 **原生体验**：Material Design 设计，流畅的原生 Android 体验
- 🔌 **多模型支持**：兼容任何支持 OpenAI 格式和图片理解的模型 API

## 🏗️ 架构对比

| 特性 | Open-AutoGLM (原版) | AutoPhone (本项目) |
|------|---------------------|------------------------------|
| 运行环境 | 电脑 (Python) | 手机 (Android App) |
| 设备连接 | 需要 ADB/USB 连接 | 无需连接，独立运行 |
| 权限获取 | ADB shell 命令 | Shizuku 服务 |
| 文本输入 | ADB Keyboard | 内置 AutoPhone Keyboard |
| 用户界面 | 命令行 | 原生 Android UI + 悬浮窗 |
| 截图方式 | ADB screencap | Shizuku shell 命令 |

## 📋 功能特性

### 核心功能
- ✅ **任务执行**：输入自然语言任务描述，AI 自动规划并执行
- ✅ **屏幕理解**：截图 → 视觉模型分析 → 输出操作指令
- ✅ **多种操作**：点击、滑动、长按、双击、输入文本、启动应用等
- ✅ **任务控制**：暂停、继续、取消任务执行
- ✅ **历史记录**：保存任务执行历史，支持查看详情和截图

### 用户界面
- ✅ **主界面**：任务输入、状态显示、快捷操作
- ✅ **悬浮窗**：实时显示执行步骤、思考过程、操作结果
- ✅ **设置页面**：模型配置、Agent 参数、多配置管理
- ✅ **历史页面**：任务历史列表、详情查看、截图标注

### 高级功能
- ✅ **多模型配置**：支持保存多个模型配置，快速切换
- ✅ **任务模板**：保存常用任务，一键执行
- ✅ **自定义 Prompt**：支持自定义系统提示词
- ✅ **快捷磁贴**：通知栏快捷磁贴，快速打开悬浮窗

## 📱 系统要求

- **Android 版本**：Android 7.0 (API 24) 及以上
- **必需应用**：[Shizuku](https://shizuku.rikka.app/) (用于获取系统权限)
- **网络连接**：需要连接到模型 API 服务（支持任何 OpenAI 格式兼容的视觉模型）
- **权限要求**：
  - 悬浮窗权限 (用于显示悬浮窗)
  - 网络权限 (用于 API 通信)
  - Shizuku 权限 (用于执行系统操作)

## 🚀 快速开始

### 1. 安装 Shizuku

Shizuku 是一个让普通应用使用系统 API 的工具，本应用依赖它来执行屏幕操作。

1. 从 [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) 或 [GitHub](https://github.com/RikkaApps/Shizuku/releases) 下载安装 Shizuku
2. 启动 Shizuku 并按照指引激活服务：
   - **无线调试方式** (推荐)：开启开发者选项 → 无线调试 → 配对设备
   - **ADB 方式**：连接电脑执行 `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`
   - **Root 方式**：如果设备已 Root，直接授权即可

### 2. 安装 AutoPhone

1. 从 [Releases](https://github.com/Luokavin/AutoPhone/releases) 下载最新 APK
2. 安装 APK 并打开应用
3. 授予 Shizuku 权限（点击"请求权限"按钮）
4. 授予悬浮窗权限（点击"授予权限"按钮）
5. 启用 AutoPhone Keyboard（点击"启用键盘"按钮）

### 3. 配置模型服务

进入设置页面，配置模型 API。本应用使用标准的 **OpenAI API 格式**，支持任何兼容该格式且具备图片理解能力的模型。

**模型要求**：
- ✅ 兼容 OpenAI `/chat/completions` API 格式
- ✅ 支持多模态输入（文本 + 图片）
- ✅ 能够理解屏幕截图并输出操作指令

**推荐模型配置**：

| 模型服务 | Base URL | Model | 获取 API Key |
|---------|----------|-------|-------------|
| 智谱 BigModel (推荐) | `https://open.bigmodel.cn/api/paas/v4` | `autoglm-phone` | [智谱开放平台](https://open.bigmodel.cn/) |
| ModelScope | `https://api-inference.modelscope.cn/v1` | `ZhipuAI/AutoGLM-Phone-9B` | [ModelScope](https://modelscope.cn/) |

**使用其他第三方模型**：

只要模型服务满足以下条件，即可在本应用中使用：

1. **API 格式兼容**：提供 OpenAI 兼容的 `/chat/completions` 端点
2. **多模态支持**：支持 `image_url` 格式的图片输入
3. **图片理解能力**：能够分析屏幕截图并理解 UI 元素

常见的兼容服务示例：
- OpenAI GPT-4V / GPT-4o（需要自行适配 prompt）
- Claude 3 系列（通过兼容层）
- 其他支持 OpenAI 格式的视觉模型 API

> ⚠️ **注意**：非 AutoGLM 模型可能需要调整系统提示词才能正确输出操作指令格式。可在设置 → 高级设置中自定义系统提示词。

### 4. 开始使用

1. 在主界面输入任务描述，如："打开微信，给文件传输助手发送消息：测试"
2. 点击"开始任务"按钮
3. 悬浮窗会自动弹出，显示执行进度
4. 观察 AI 的思考过程和执行操作

## 📖 使用教程

### 基本操作

**启动任务**：
1. 在主界面或悬浮窗输入任务描述
2. 点击"开始"按钮
3. 应用会自动截图、分析、执行操作

**控制任务**：
- **暂停**：点击悬浮窗的暂停按钮，任务会在当前步骤后暂停
- **继续**：点击继续按钮恢复执行
- **停止**：点击停止按钮取消任务

**查看历史**：
1. 点击主界面的历史按钮
2. 查看所有执行过的任务
3. 点击任务查看详细步骤和截图

### 任务示例

```
# 社交通讯
打开微信，搜索张三并发送消息：你好

# 购物搜索
打开淘宝，搜索无线耳机，按销量排序

# 外卖点餐
打开美团，搜索附近的火锅店

# 出行导航
打开高德地图，导航到最近的地铁站

# 视频娱乐
打开抖音，刷5个视频
```

### 高级功能

**保存模型配置**：
1. 进入设置 → 模型配置
2. 配置好参数后点击"保存配置"
3. 输入配置名称保存
4. 之后可以快速切换不同配置

**创建任务模板**：
1. 进入设置 → 任务模板
2. 点击"添加模板"
3. 输入模板名称和任务描述
4. 在主界面点击模板按钮快速选择

**自定义系统提示词**：
1. 进入设置 → 高级设置
2. 编辑系统提示词
3. 可以添加特定领域的指令增强

## 🛠️ 开发教程

### 环境准备

**开发工具**：
- Android Studio Hedgehog (2023.1.1) or higher
- JDK 11 or higher
- Kotlin 1.9.x

**克隆项目**：
```bash
git clone https://github.com/your-repo/AutoPhone.git
cd AutoPhone
```

**Open Project**:
1. Launch Android Studio
2. Select "Open an existing project"
3. Select project root directory
4. Wait for Gradle sync to complete

### Project Structure

```
app/src/main/java/com/kevinluo/autoglm/
├── action/                 # 动作处理模块
├── agent/                  # Agent 核心模块
├── app/                    # 应用基础模块
├── config/                 # 配置模块
├── device/                 # 设备操作模块
├── history/                # 历史记录模块
├── input/                  # 输入模块
├── model/                  # 模型通信模块
├── screenshot/             # Screenshot module
├── settings/               # Settings module
├── ui/                     # UI 模块
├── util/                   # Utility module
├── ComponentManager.kt     # Component manager
├── MainActivity.kt         # Main activity
└── UserService.kt          # Shizuku user service
```

### Build and Debug

**Debug Build**:
```bash
./gradlew assembleDebug
```

**Release Build**:
```bash
./gradlew assembleRelease
```

**Run Tests**:
```bash
./gradlew test
```

**Install to Device**:
```bash
./gradlew installDebug
```

## 🔧 常见问题

| 问题 | 解决方案 |
|------|----------|
| **Shizuku 显示未运行？** | 确保 Shizuku 已安装并打开，按指引激活服务，推荐使用无线调试方式 |
| **每次重启后 Shizuku 失效？** | 无线调试方式需重新配对，可考虑 Root 方式永久激活或设置开机自启脚本 |
| **悬浮窗权限无法授予？** | 系统设置 → 应用 → AutoPhone → 权限 → 开启"显示在其他应用上层" |
| **键盘无法启用？** | 系统设置 → 语言和输入法 → 管理键盘 → 启用 AutoPhone Keyboard |
| **点击操作不生效？** | 检查 Shizuku 是否运行，部分系统需开启"USB 调试(安全设置)"，尝试重启 Shizuku |
| **文本输入失败？** | 确保 AutoPhone Keyboard 已启用，检查输入框是否获得焦点 |
| **截图显示黑屏？** | 敏感页面（支付、密码等）的正常保护，应用会自动检测并标记 |

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。

## 🙏 致谢

- [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) - 原始开源项目
- [Shizuku](https://github.com/RikkaApps/Shizuku) - 系统权限框架
- [智谱 AI](https://www.zhipuai.cn/) - AutoGLM 模型提供方

## 📞 联系方式

- Issues: [GitHub Issues](https://github.com/your-repo/issues)
- Email: luokavin@foxmail.com

---

<div align="center">

**If this project helps you, please give it a ⭐ Star!**

</div>
