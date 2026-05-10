# SillyDroid Android

[![APK Action](https://img.shields.io/github/actions/workflow/status/jialmaster/SillyDroid/sillydroid-upstream-apk.yml?label=SillyDroid%20APK)](https://github.com/jialmaster/SillyDroid/actions/workflows/sillydroid-upstream-apk.yml)
[![Runtime Action](https://img.shields.io/github/actions/workflow/status/jialmaster/SillyDroid/sillydroid-runtime-image-release.yml?label=SillyDroid%20Runtime)](https://github.com/jialmaster/SillyDroid/actions/workflows/sillydroid-runtime-image-release.yml)
[![Runtime Image](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fjialmaster%2FSillyDroid%2Fmaster%2Fartifacts%2Fandroid-runtime-images%2Ftavern-android-runtime-linux-arm64.metadata.json&query=%24.imageFileName&label=Runtime%20Image)](https://github.com/jialmaster/SillyDroid/blob/master/artifacts/android-runtime-images/tavern-android-runtime-linux-arm64.metadata.json)
[![Runtime Generated](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fjialmaster%2FSillyDroid%2Fmaster%2Fartifacts%2Fandroid-runtime-images%2Ftavern-android-runtime-linux-arm64.metadata.json&query=%24.generatedAtUtc&label=Runtime%20Generated)](https://github.com/jialmaster/SillyDroid/blob/master/artifacts/android-runtime-images/tavern-android-runtime-linux-arm64.metadata.json)
[![Latest Release](https://img.shields.io/github/v/release/jialmaster/SillyDroid?display_name=tag&label=SillyDroid%20Version)](https://github.com/jialmaster/SillyDroid/releases/latest)
[![Release Time](https://img.shields.io/github/release-date/jialmaster/SillyDroid?label=SillyDroid%20Release%20Time)](https://github.com/jialmaster/SillyDroid/releases/latest)

这个仓库维护 SillyDroid Android 宿主、离线运行时打包链，以及基于指定 SillyTavern tag 的 APK 构建与发布流程。

上游 SillyTavern 源码不会长期作为主工程保存在仓库里；构建时会同步指定 tag，先生成 stage 3 的 server source，再由 stage 4 组合宿主使用的最终 server payload。

README 保留常用说明；更细的构建语义、阶段边界和脚本职责，以 scripts 下对应脚本开头的 Contract 注释为准。

## 构建状态说明

- APK Action / Runtime Action 徽章会显示 GitHub Actions 当前状态：成功（success）、失败（failure）和进行中（in progress）。
- Runtime 目前没有单独的语义版本号字段，README 顶部显示的是 runtime image 文件名与生成时间（来自 metadata）。
- 若后续需要展示固定 runtime 版本号，可在 runtime metadata 增加 `runtimeVersion` 字段后再接入徽章。

## 宿主当前能力

- 本地启动、停止并健康检查 Tavern 服务。
- 设置页编辑 Tavern 配置。
- 数据导入、导出、恢复默认和清空后重启。
- 第三方扩展可在宿主设置页独立安装、重新拉取、删除。
- 查看最新日志并导出当前日志文件。
- 可选的悬浮调试/日志入口。
- 基于 GitHub Release 的 APK 自更新能力。

## 功能说明

### 独立扩展安装

- 宿主设置页提供独立的扩展安装入口，不依赖先进入酒馆 Web UI。
- 可直接输入 GitHub、GitLab 或支持 git clone 的镜像仓库地址，由宿主在本地运行时里完成校验、预览和安装。
- 已安装扩展支持按 `manifest.homePage` 重新拉取，也支持直接删除。
- 这条链路面向宿主自己的 `extensions` 持久目录，更新 APK 不会直接覆盖用户已安装的第三方扩展。

### 原酒馆数据迁移

- 设置页导入会自动识别上游 Tavern 用户备份 ZIP，以及仅包含 `config`、`data`、`plugins`、`extensions` 四目录的数据快照 ZIP。
- 从原版酒馆迁移时，可导入官方用户备份，把主要用户数据迁入当前 Android 宿主。
- 如果手头是宿主 / Docker / Linux / Termux 导出的四目录数据包，则会恢复 `config`、`data`、`plugins`、`extensions` 四个持久目录；其中 Linux / Termux 也支持把 `public/scripts/extensions/third-party` 识别为扩展目录导入。
- 导入前会先做类型识别和确认，不会在未确认的情况下直接覆盖现有数据。

### Termux 一键导出数据

- 仓库内提供了 [scripts/export-tavern-data.sh](scripts/export-tavern-data.sh)，用于在官方 Termux 版 SillyTavern 环境里一键导出数据包。
- 这个脚本只导出 `config`、`data`、`plugins`、`extensions` 四个目录，不会把程序本体、`node_modules`、源码或其他运行时文件打进 ZIP。
- 默认会自动探测官方 Termux 安装目录（通常是 `~/SillyTavern`），自动识别扩展目录（`extensions` 或 `public/scripts/extensions/third-party`），并优先尝试导出到手机共享存储。
- 不需要先 clone 这个仓库；直接下载脚本再执行即可。若缺少 `zip`，脚本会自动尝试执行 `pkg install -y zip`。

在 Termux 里执行这一句即可：

```bash
curl -fsSL https://raw.githubusercontent.com/jialmaster/SillyDroid/master/scripts/export-tavern-data.sh | bash
```

如果第一次没有授权共享存储，先执行：

```bash
termux-setup-storage
```

如需手工指定安装目录或输出目录：

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/jialmaster/SillyDroid/master/scripts/export-tavern-data.sh) \
   --install-root "$HOME/SillyTavern" \
   --output-dir "$HOME/storage/shared/Download"
```

脚本会输出：

- 当前是否为 Termux 环境。
- 检测到的 SillyTavern 安装目录。
- 检测到的配置、数据、插件、扩展目录。
- 是否可直接保存到手机共享存储。
- 最终 ZIP 路径。

### 扩展数据边界

- 会被备份和导入的是持久数据目录里的扩展内容，也就是当前宿主使用的 `extensions` 持久目录。
- 对于 Linux / Termux 风格的数据包，若扩展位于 `public/scripts/extensions/third-party`，导入时也会自动映射到宿主的 `extensions` 持久目录。
- 扩展自己生成的用户数据如果写在 `data` 目录下，也会随 `data` 一起备份和导入。
- 不会被备份和导入的是程序本体自带的 server 文件、`public` 里的其他静态资源、APK 内置 `bundled-extensions` 资产，以及重新安装 APK 后可再生成的非持久运行时文件。

## 当前项目包含

1. `android-tavern/`
   Android 宿主工程，应用包名为 `com.jm.sillydroid`。
2. `scripts/build-tavern-android-local.sh`
   本地一键入口，顺序执行 rootfs、dependency packs、server source、apk 四个阶段。
3. `scripts/resolve-tavern-build-plan.sh`
   统一解析宿主版本、上游 tag、变更判定、release 命名和 `artifacts/releases/...` 输出路径。
4. `scripts/build-tavern-android-runtime-image.sh`
   生成 Tavern 专用 runtime image 及 metadata。
5. `scripts/sync-android-rootfs.sh`
   生成离线 Linux rootfs、proot 及相关运行时资产。
6. `scripts/build-tavern-dependency-packs.sh`
   单独构建 `node`、`git` 等 dependency pack zip。
7. `scripts/sync-tavern-android-bootstrap.sh`
   下载指定 SillyTavern tag，生成 `server-source.zip`；只包含 Tavern 源码、overlay 和 npm 运行依赖，不包含 dependency packs，也不直接产出最终 server payload。
8. `scripts/build-tavern-android-apk.sh`
   当前是阶段 4 脚本，只消费现有 runtime image、server source、dependency packs 组装 debug 或 release APK，不再作为推荐的一键总入口。
9. `scripts/android-build-common.sh`
   Android/WSL 构建公共环境脚本。

## 前置要求

- Windows + WSL，或直接 Linux bash 环境。
- `bash`、`curl`、`unzip`、`tar`、`sha256sum`、`realpath`。
- 当前链路只支持 `linux-arm64`。
- Android SDK/JDK 不要求先手工装齐；构建脚本会按当前实现自动补齐缺失部分。

## 快速开始

建议在 WSL 或 Linux bash 环境里直接执行：

```bash
git clone https://github.com/jialmaster/SillyDroid.git
cd SillyDroid
bash ./scripts/build-tavern-android-local.sh
```

如果需要固定上游版本或构建类型，可以显式传参：

```bash
bash ./scripts/build-tavern-android-local.sh --tavern-tag 1.18.0 --build-type debug
```

## 补充说明

- 默认构建配置见 `sillydroid-build-config.json`。
- 当前本地入口脚本为 `scripts/build-tavern-android-local.sh`。
- 默认 APK 输出目录为 `artifacts/releases/android-apk/`。

## 免责声明

- 本项目仅供学习、研究、技术验证与交流使用。
- 使用者应自行确认本项目及相关上游项目、扩展、模型、数据与网络访问行为符合当地法律法规、平台规则及许可证要求。
- 因部署、二次开发、插件安装、数据迁移、第三方服务接入或其他实际使用行为产生的风险与责任，由使用者自行承担。
- 本项目不对任何生产环境、商业用途、合规适配或特定业务结果作明示或默示保证。


