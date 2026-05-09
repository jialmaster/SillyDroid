# ST.AI SillyTavern Android

[![APK Action](https://img.shields.io/github/actions/workflow/status/jialmaster/ST.AI.SillyTavern.Android/tavern-upstream-apk.yml?label=APK%20Action)](https://github.com/jialmaster/ST.AI.SillyTavern.Android/actions/workflows/tavern-upstream-apk.yml)
[![Runtime Action](https://img.shields.io/github/actions/workflow/status/jialmaster/ST.AI.SillyTavern.Android/tavern-runtime-image-release.yml?label=Runtime%20Action)](https://github.com/jialmaster/ST.AI.SillyTavern.Android/actions/workflows/tavern-runtime-image-release.yml)
[![Latest Release](https://img.shields.io/github/v/release/jialmaster/ST.AI.SillyTavern.Android?display_name=tag&label=Latest%20Version)](https://github.com/jialmaster/ST.AI.SillyTavern.Android/releases/latest)
[![Release Time](https://img.shields.io/github/release-date/jialmaster/ST.AI.SillyTavern.Android?label=Release%20Time)](https://github.com/jialmaster/ST.AI.SillyTavern.Android/releases/latest)

这个仓库维护 ST.AI 的 SillyTavern Android 宿主、离线运行时打包链，以及基于指定 SillyTavern tag 的 APK 构建与发布流程。

上游 SillyTavern 源码不会长期作为主工程保存在仓库里；构建时会同步指定 tag，先生成 stage 3 的 server source，再由 stage 4 组合宿主使用的最终 server payload。

## 第三方许可证说明

- Android 运行时层基于 Termux 生态的 prefix、bootstrap 约定和相关运行时资源构建。
- 分发 APK、runtime image 或解包后的 payload 时，应同时保留上游组件的版权声明与许可证文本。
- 当前仓库整理的第三方许可证说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

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
- `scripts/resolve-tavern-build-plan.sh`
  统一解析宿主版本、上游 tag、变更判定、release 命名和 `artifacts/releases/...` 输出路径。
- `scripts/sync-android-rootfs.sh`
  生成离线 Linux rootfs、proot 及相关运行时资产。
- `scripts/sync-tavern-android-bootstrap.sh`
  下载指定 SillyTavern tag，生成 `server-source.zip`；只包含 Tavern 源码、overlay 和 npm 运行依赖，不包含 dependency packs，也不直接产出最终 server-payload。
- `scripts/build-tavern-dependency-packs.sh`
  单独构建 `node`、`git` 等 dependency pack zip。
- `scripts/build-tavern-android-runtime-image.sh`
  生成 Tavern 专用 runtime image 及 metadata。
- `scripts/build-tavern-android-local.sh`
  本地一键入口，顺序执行 rootfs、dependency packs、server source、apk 四个阶段。
- `scripts/build-tavern-android-apk.sh`
  当前是阶段 4 脚本，只消费现有 runtime image、server source、dependency packs 组装 debug 或 release APK，不再作为推荐的一键总入口。
- `.github/workflows/`
  包含 runtime 资产发布与 upstream APK 发布两条工作流。

## 前置要求

- Windows + WSL，或直接 Linux bash 环境。
- `bash`、`curl`、`unzip`、`tar`、`sha256sum`、`realpath`。
- 当前链路只支持 `linux-arm64`。
- Android SDK/JDK 不要求先手工装齐；构建脚本会按当前实现自动补齐缺失部分。

## 本地推荐流程

本地一键入口已经切到 `scripts/build-tavern-android-local.sh`。它会先走统一的 build plan，再顺序执行 4 个阶段脚本：rootfs、dependency packs、server source、apk。

```bash
bash ./scripts/build-tavern-android-local.sh \
  --build-type debug \
  --tavern-tag 1.18.0
```

推荐把这个脚本视为本地总入口；`scripts/build-tavern-android-apk.sh` 现在只负责第 4 阶段。如果你已经准备好了前置物料，仍然可以直接调用它做最终组装；它不会再隐式补齐 prerequisites。

正常本地打包只需要调用这一条入口；除非你是在排查某个阶段本身，否则不要跳过它直接拼装自己的阶段组合。

每个阶段脚本开头都带有 `Stage Contract` 或 `Build Plan Contract` 注释。后续如果要调整阶段职责，必须先同步修改这些脚本头部契约和本节下方的“四阶段边界”，再改实现。

`stai-build-config.json` 里的 `build.includeDependencyPacks` 现在支持两种语义：

- 配置项缺失或格式非法时，回退默认 `node,git`。
- 显式配置为空数组 `[]` 时，禁用 dependency packs，stage 2 只生成空索引，stage 4 直接依赖 rootfs 提供运行时。

默认会生成这些产物：

- `./artifacts/releases/rootfs/linux-arm64/tavern-rootfs-linux-arm64.zip`
- `./artifacts/releases/dependency-packs/linux-arm64/*.zip`
- `./artifacts/releases/server-source/linux-arm64/<tag>/server-source.zip`
- `./artifacts/releases/android-apk/app-debug.apk`
- `./artifacts/releases/android-apk/stai-sillytavern-android-v<宿主版本>-<上游tag>-<build_type>.apk`
- `./artifacts/releases/android-apk/stai-sillytavern-android-v<宿主版本>-<上游tag>-<build_type>.apk.sha256`
- `./artifacts/releases/android-apk/stai-sillytavern-android-v<宿主版本>-<上游tag>-<build_type>.update.json`

注意：`resolve-tavern-build-plan.sh` 在 `--tavern-tag auto/latest` 时会访问 GitHub API 解析最新上游 release tag；release 序号查询在 API 不可用或命中 rate limit 时会自动回落到 `0`。如果本地命中 GitHub rate limit，优先显式传 `--tavern-tag <tag>`。

## 四阶段边界

1. Stage 0 / build plan：`scripts/resolve-tavern-build-plan.sh`
  只负责解析版本、变更判定、release 命名和统一输出路径；不能下载、构建或修改任何 stage 产物。
2. Stage 1 / runtime image：`scripts/build-tavern-android-runtime-image.sh`
  只负责生成 rootfs/runtime image 和 metadata；不能构建 dependency packs、server source 或 APK。
3. Stage 2 / dependency packs：`scripts/build-tavern-dependency-packs.sh`
  只负责生成 `node`、`git` 等 dependency pack zip 和 manifest；不能构建 rootfs、server source 或 APK。
4. Stage 3 / server source：`scripts/sync-tavern-android-bootstrap.sh`
  只负责同步指定 Tavern tag、安装 npm 运行依赖、应用 overlay，并输出 `server-source.zip` 与 `server-source-manifest.json`；不能打入 dependency packs，也不能生成最终 `server-payload`。
5. Stage 4 / APK assembly：`scripts/build-tavern-android-apk.sh`
  只负责消费 stage 1、2、3 的产物，在 stage 4 临时目录里组合最终 `server-payload`，再写入 Android 工程并组装 APK；不能隐式回补前置阶段。
6. Local one-click：`scripts/build-tavern-android-local.sh`
  这是正常本地打包入口，只负责按同一套路径与语义依次调用上面 4 个阶段，不允许自己追加一套独立打包逻辑。

## 分步构建

只有在你需要单独验证某个阶段时，才建议直接调用下面这些单阶段脚本。

1. 生成 Tavern runtime image

```bash
bash ./scripts/build-tavern-android-runtime-image.sh \
  --runtime-rid linux-arm64 \
  --output ./artifacts/releases/rootfs/linux-arm64/tavern-rootfs-linux-arm64.zip
```

2. 生成 dependency packs

```bash
bash ./scripts/build-tavern-dependency-packs.sh \
  --runtime-rid linux-arm64
```

3. 生成 Tavern server source

```bash
bash ./scripts/sync-tavern-android-bootstrap.sh \
  --runtime-rid linux-arm64 \
  --tag 1.18.0 \
  --target-root ./artifacts/releases/server-source/linux-arm64/1.18.0
```

4. 组装 Tavern APK

```bash
bash ./scripts/build-tavern-android-apk.sh \
  --runtime-rid linux-arm64 \
  --build-type debug \
  --tag 1.18.0
```

5. 安装到设备（可选）

```powershell
adb install -r .\artifacts\releases\android-apk\app-debug.apk
```

6. 验证真机上的 system git 链路（可选）

这个脚本会直接复用宿主真实的 [android-tavern/app/src/main/assets/bootstrap/scripts/start-server.sh](android-tavern/app/src/main/assets/bootstrap/scripts/start-server.sh) 启动链，在设备里临时注入一个探针 entrypoint，用 Node `spawnSync('git', ...)` 依次验证：

- `git --version`
- `git ls-remote --heads <repo>`

```bash
python ./scripts/validate-android-system-git.py
```

可选参数：

- `--serial <adb-serial>` 指定设备
- `--package <package>` 指定应用包名
- `--repo-url <git-url>` 指定远端仓库
- `--branch <branch>` 额外验证某个分支 head

## 版本与签名规则

- `android-tavern/gradle.properties` 中的 `staiAndroidHostVersion` 是宿主版本基线，目前为 `1.0.1`。
- 本地一键脚本和 GitHub Actions 都通过 `scripts/resolve-tavern-build-plan.sh` 统一计算版本。
- 当前宿主版本规则是 `<基线版本>.<当前仓库提交计数>`，例如 `1.0.1.7`。
- 当前 `versionName` 规则是 `<宿主版本>+tavern.<上游tag>`，例如 `1.0.1.7+tavern.1.18.0`。
- 当前 `versionCode` 规则是 `1800000000 + 提交计数 * 1000 + 上游 release 序号`，用于保证对同一宿主版本线的单调递增。
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
- `artifacts/releases/` 是当前统一的构建产物根目录：rootfs、dependency packs、server source、android apk 都在这里汇总。
- `artifacts/validation/` 现在主要保留真机验证产物，例如 UI 截图与 XML dump。

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
- 当前 workflow 结构是 `plan -> rootfs_prereq -> dependency_prereq -> server_prereq -> build -> publish`。
- 其中 `rootfs_prereq`、`dependency_prereq`、`server_prereq` 三个 job 会分别判断当前阶段是 `built` 还是复用已有 GitHub Release 资产。
- `build` job 只消费 `artifacts/releases/...` 下的前置物料，不再负责隐式准备整套 prerequisites。
- 如果最终 APK release 已存在，且三个前置阶段都没有重新构建，`build` job 会优先复用现有 release 资产，而不是重新 assemble。
- `plan` job 负责统一版本、路径和 release 命名；它在 `auto/latest` 模式下也会访问 GitHub API 解析最新上游 tag。

APK 工作流会额外产出：

- `<artifact>.apk`
- `<artifact>.apk.sha256`
- `<artifact>.update.json`

其中 `.update.json` 用于宿主的更新检测与下载校验。

### Release 命名规则

- Rootfs release tag：`tavern-rootfs-linux-arm64`
- Dependency packs release tag：`tavern-dependency-packs-linux-arm64`
- Server source release tag：`tavern-server-source-linux-arm64-<上游tag>`
- APK release tag：`stai-sillytavern-v<宿主版本>-<上游tag>-<build_type>`
- APK 资产名：`stai-sillytavern-android-v<宿主版本>-<上游tag>-<build_type>.apk`

工作流当前只跟踪上游 GitHub Release tag，不跟踪上游每一次普通提交。

## 免责声明

- 本项目仅供学习、研究、技术验证与交流使用。
- 使用者应自行确认本项目及相关上游项目、扩展、模型、数据与网络访问行为符合当地法律法规、平台规则及许可证要求。
- 因部署、二次开发、插件安装、数据迁移、第三方服务接入或其他实际使用行为产生的风险与责任，由使用者自行承担。
- 本项目不对任何生产环境、商业用途、合规适配或特定业务结果作明示或默示保证。