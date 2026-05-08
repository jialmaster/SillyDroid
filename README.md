# ST.AI SillyTavern Android

[![APK Action](https://img.shields.io/github/actions/workflow/status/jialmaster/ST.AI.SillyTavern.Android/tavern-upstream-apk.yml?label=APK%20Action)](https://github.com/jialmaster/ST.AI.SillyTavern.Android/actions/workflows/tavern-upstream-apk.yml)
[![Runtime Action](https://img.shields.io/github/actions/workflow/status/jialmaster/ST.AI.SillyTavern.Android/tavern-runtime-image-release.yml?label=Runtime%20Action)](https://github.com/jialmaster/ST.AI.SillyTavern.Android/actions/workflows/tavern-runtime-image-release.yml)
[![Latest Release](https://img.shields.io/github/v/release/jialmaster/ST.AI.SillyTavern.Android?display_name=tag&label=Latest%20Version)](https://github.com/jialmaster/ST.AI.SillyTavern.Android/releases/latest)
[![Release Time](https://img.shields.io/github/release-date/jialmaster/ST.AI.SillyTavern.Android?label=Release%20Time)](https://github.com/jialmaster/ST.AI.SillyTavern.Android/releases/latest)

这个仓库维护 ST.AI 的 SillyTavern Android 宿主、离线运行时打包链，以及基于指定 SillyTavern tag 的 APK 构建与发布流程。

上游 SillyTavern 源码不会长期作为主工程保存在仓库里；构建时会同步指定 tag，生成宿主使用的 server payload。

## 宿主当前能力

- 本地启动、停止并健康检查 SillyTavern 服务。
- 设置页编辑 Tavern 配置。
- 数据导入、导出、恢复默认和清空后重启。
- 第三方扩展可在宿主设置页独立安装、重新拉取、删除。
- 查看最新日志并导出当前日志文件。
- 可选的悬浮调试/日志入口。
- 基于 GitHub Release 的 APK 自更新能力。

## 功能说明

### 独立扩展安装

- 宿主设置页提供独立的扩展安装入口，不依赖先进入酒馆 Web UI。
- 可直接输入 GitHub 或 GitLab 仓库地址，由宿主在本地运行时里完成校验、预览和安装。
- 已安装扩展支持按 `manifest.homePage` 重新拉取，也支持直接删除。
- 这条链路面向宿主自己的 `extensions` 持久目录，更新 APK 不会直接覆盖用户已安装的第三方扩展。

### 原酒馆数据迁移

- 设置页导入会自动识别官方 SillyTavern 用户备份 ZIP，以及宿主自己的全量数据快照 ZIP。
- 从原版酒馆迁移时，可导入官方用户备份，把主要用户数据迁入当前 Android 宿主。
- 如果手头是宿主全量快照，则会按宿主目录结构恢复 `config`、`data`、`plugins`、`extensions`，用于更完整地迁移当前运行环境。
- 导入前会先做类型识别和确认，不会在未确认的情况下直接覆盖现有数据。

## 当前项目包含

- `android-tavern/`
  Android 宿主工程，应用包名为 `com.stai.sillytavern`。
- `scripts/android-build-common.sh`
  Android/WSL 构建公共环境脚本。
- `scripts/sync-android-rootfs.sh`
  生成离线 Linux rootfs、proot 及相关运行时资产。
- `scripts/sync-tavern-android-bootstrap.sh`
  下载指定 SillyTavern tag 与 Linux arm64 Node runtime，生成 `server-payload.zip`。
- `scripts/build-tavern-android-runtime-image.sh`
  生成 Tavern 专用 runtime image 及 metadata。
- `scripts/build-tavern-android-apk.sh`
  向 `android-tavern/` 注入 runtime image 与 server package，组装 debug 或 release APK。
- `.github/workflows/`
  包含 runtime 资产发布与 upstream APK 发布两条工作流。

## 前置要求

- Windows + WSL，或直接 Linux bash 环境。
- `bash`、`curl`、`unzip`、`tar`、`sha256sum`、`realpath`。
- 当前链路只支持 `linux-arm64`。
- Android SDK/JDK 不要求先手工装齐；构建脚本会按当前实现自动补齐缺失部分。

## 最短路径

在 Linux 或 WSL bash 中，在仓库根目录执行：

```bash
bash ./scripts/build-tavern-android-apk.sh \
  --runtime-rid linux-arm64 \
  --build-type debug \
  --tag 1.18.0
```

如果你在 Windows PowerShell 中直接调用，通常会这样写：

```powershell
wsl.exe bash -lc "cd /mnt/d/Git/ST.AI.SillyTavern.Android && bash ./scripts/build-tavern-android-apk.sh --runtime-rid linux-arm64 --build-type debug --tag 1.18.0"
```

默认会生成这些产物：

- `./artifacts/validation/android-tavern-server-package/linux-arm64/server-payload.zip`
- `./artifacts/android-runtime-images/tavern-android-runtime-linux-arm64.zip`
- `./artifacts/validation/android-tavern/app-debug.apk`

## 分步构建

1. 生成 Tavern server package

```bash
bash ./scripts/sync-tavern-android-bootstrap.sh \
  --runtime-rid linux-arm64 \
  --tag 1.18.0 \
  --target-root ./artifacts/validation/android-tavern-server-package/linux-arm64
```

2. 生成 Tavern runtime image

```bash
bash ./scripts/build-tavern-android-runtime-image.sh \
  --runtime-rid linux-arm64 \
  --output ./artifacts/android-runtime-images/tavern-android-runtime-linux-arm64.zip
```

3. 组装 Tavern APK

```bash
bash ./scripts/build-tavern-android-apk.sh \
  --runtime-rid linux-arm64 \
  --build-type debug \
  --runtime-image ./artifacts/android-runtime-images/tavern-android-runtime-linux-arm64.zip \
  --server-package ./artifacts/validation/android-tavern-server-package/linux-arm64/server-payload.zip
```

4. 安装到设备（可选）

```powershell
adb install -r .\artifacts\validation\android-tavern\app-debug.apk
```

## 版本与签名规则

- `android-tavern/gradle.properties` 中的 `staiAndroidHostVersion` 是宿主版本基线，目前为 `1.0.1`。
- 本地构建默认 `versionName` 规则是 `<宿主版本>+tavern.<上游tag>`，例如 `1.0.1+tavern.1.18.0`。
- GitHub Actions 会把宿主版本自动计算为 `<基线版本>.<当前仓库提交计数>`，例如 `1.0.1.7`。
- 工作流里的 `versionCode` 默认使用 UTC epoch 秒，保证 release 构建单调递增。
- 本地和 CI 的 `release` 构建都优先读取 `STAI_ANDROID_RELEASE_*` 环境变量；如果未提供，会回退到仓库内：
  - `android-tavern/app/signing/release.keystore`
  - `android-tavern/app/signing/release-signing.properties`
- 如果环境变量和仓库内回退签名都不存在，`release` 构建会直接失败。

## 生成物说明

- `android-tavern/app/src/main/assets/bootstrap/rootfs`
- `android-tavern/app/src/main/assets/bootstrap/server`
- `android-tavern/app/src/main/jniLibs/arm64-v8a`

上面这些运行时内容都由脚本在构建时注入，不是长期维护入口。

- `artifacts/tmp/` 下的 upstream payload/source 是临时同步或验证产物，只用于调试与检查，不建议直接编辑。
- `artifacts/validation/` 下主要存放本地验证产物，例如 APK、server package、UI 截图与 XML dump。

## GitHub Actions

仓库当前拆成两条工作流：

- `.github/workflows/tavern-runtime-image-release.yml`
  负责构建并发布 runtime image。
- `.github/workflows/tavern-upstream-apk.yml`
  负责下载最新 runtime image、同步指定 upstream tag 的 server package，并组装 APK。

### Runtime 工作流

- 触发路径：
  - `scripts/android-build-common.sh`
  - `scripts/build-tavern-android-runtime-image.sh`
  - `scripts/sync-android-rootfs.sh`
  - `android-tavern/app/src/main/assets/bootstrap/scripts/**`
- 固定 release tag：`tavern-runtime-linux-arm64`
- 固定 release 资产：
  - `tavern-android-runtime-linux-arm64.zip`
  - `tavern-android-runtime-linux-arm64.zip.sha256`
  - `tavern-android-runtime-linux-arm64.metadata.json`

### APK 工作流

- 定时任务每 6 小时检查一次 `SillyTavern/SillyTavern` 最新 GitHub Release tag。
- 手动触发时可指定 `tavern_tag`、`build_type`、`force_rebuild`、`publish_release`。
- `push`、定时任务和手动触发默认构建 `release` APK。
- 如果当前仓库已存在同 upstream tag、同 build type 的 APK release 资产，工作流会自动跳过。
- 如果本次 `push` 只改动了 runtime 源目录，APK 工作流会先跳过，等 runtime 工作流发布最新 runtime 资产后再自动重触发。

APK 工作流会额外产出：

- `<artifact>.apk`
- `<artifact>.apk.sha256`
- `<artifact>.update.json`

其中 `.update.json` 用于宿主的更新检测与下载校验。

### Release 命名规则

- APK release tag：`stai-sillytavern-v<宿主版本>-<上游tag>-<build_type>`
- APK 资产名：`stai-sillytavern-android-v<宿主版本>-<上游tag>-<build_type>.apk`

工作流当前只跟踪上游 GitHub Release tag，不跟踪上游每一次普通提交。

## 免责声明

- 本项目仅供学习、研究、技术验证与交流使用。
- 使用者应自行确认本项目及相关上游项目、扩展、模型、数据与网络访问行为符合当地法律法规、平台规则及许可证要求。
- 因部署、二次开发、插件安装、数据迁移、第三方服务接入或其他实际使用行为产生的风险与责任，由使用者自行承担。
- 本项目不对任何生产环境、商业用途、合规适配或特定业务结果作明示或默示保证。