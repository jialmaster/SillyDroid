# ST.AI SillyTavern Android

这个仓库只保留 SillyTavern Android 宿主与打包链。

## 包含内容

- `android-tavern/`
  独立 Android 宿主工程，包名为 `com.stai.sillytavern`。
- `scripts/android-build-common.sh`
  Android/WSL 打包公共环境脚本。
- `scripts/sync-android-rootfs.sh`
  生成 Tavern 宿主使用的离线 Linux rootfs 与 proot 运行时资产。
- `scripts/sync-tavern-android-bootstrap.sh`
  下载指定 SillyTavern tag 与 Linux arm64 Node runtime，生成 `server-payload.zip`。
- `scripts/build-tavern-android-runtime-image.sh`
  生成 Tavern 专用 runtime image。
- `scripts/build-tavern-android-apk.sh`
  向 `android-tavern/` 注入 runtime image 与 server package，输出 debug 或 release APK。

## 前置要求

- Windows + WSL，或直接 Linux bash 环境。
- `bash`、`curl`、`unzip`、`tar`、`sha256sum`、`realpath`。
- Android SDK/JDK 不需要提前手工装齐，脚本会按当前实现自动补齐缺失部分。

## 最短路径

在仓库根目录执行：

```bash
bash ./scripts/build-tavern-android-apk.sh \
  --runtime-rid linux-arm64 \
  --build-type debug \
  --tag 1.18.0
```

默认产物：

- `./artifacts/validation/android-tavern-server-package/linux-arm64/server-payload.zip`
- `./artifacts/android-runtime-images/tavern-android-runtime-linux-arm64.zip`
- `./artifacts/validation/android-tavern/app-debug.apk`

## 分步打包

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

## 说明

- 当前链路只支持 `linux-arm64`。
- `android-tavern/app/src/main/assets/bootstrap/rootfs`、`android-tavern/app/src/main/assets/bootstrap/server` 与 `android-tavern/app/src/main/jniLibs/arm64-v8a` 中的运行时生成物不入库，由脚本在打包时写入。
- release APK 仍然要求提供正式签名；未签名产物会直接失败。

## GitHub Actions

仓库内现在拆成两条工作流：

- `.github/workflows/tavern-runtime-image-release.yml`
  只负责构建 Linux runtime + Node 环境包，并把最新资产覆盖发布到固定 Release tag `tavern-runtime-linux-arm64`。
- `.github/workflows/tavern-upstream-apk.yml`
  只负责下载上面这份最新 runtime 环境包，再同步指定上游 SillyTavern tag 的 server package，最后组装 APK。

### Runtime 环境包发布规则

- 监控目录：`scripts/android-build-common.sh`、`scripts/sync-android-rootfs.sh`、`scripts/build-tavern-android-runtime-image.sh`、`android-tavern/app/src/main/assets/bootstrap/scripts/`。
- 只要这些路径有变更，`tavern-runtime-image-release` 就会重打环境包并覆盖同一个 Release tag 下的最新资产。
- 环境包固定 Release tag：`tavern-runtime-linux-arm64`
- 环境包固定资产名：`tavern-android-runtime-linux-arm64.zip`
- APK 工作流始终下载这份最新环境包，因此后续常规打包不再重新编译 runtime，只打 APK + 酒馆 server package。
- 如果是 runtime 源目录发生变化的 push，APK 工作流会先跳过，等 runtime 工作流发布完最新环境包后，再自动重触发一次 release APK 构建，避免用到旧环境包。

### APK 自动发布规则

- 定时触发：每 6 小时检查一次 `SillyTavern/SillyTavern` 的最新 GitHub Release tag。
- 自动去重：如果当前仓库已经存在同一上游 tag 和同一 build type 的 APK release 资产，则跳过，不重复打包。
- 手动触发：可手动指定 `tavern_tag`、`build_type`、`force_rebuild` 与 `publish_release`。
- 默认行为：push、定时任务和手动触发默认都打 `release` APK，并上传到当前仓库 GitHub Release。

### Release 签名密钥

Actions 里打 `release` APK 优先读取下面四个 Secrets：

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

其中 `ANDROID_RELEASE_KEYSTORE_BASE64` 需要是 keystore 文件的 base64 文本。

如果这 4 个 Secrets 一个都没配，工作流会自动回退到仓库内的：

- `android-tavern/app/signing/release.keystore`
- `android-tavern/app/signing/release-signing.properties`

### 自动发布的 Release 规则

- Release tag 格式：`sillytavern-<上游tag>-<build_type>`
- APK 资产名格式：`sillytavern-android-<上游tag>-<build_type>.apk`

工作流只监控上游 GitHub Release tag，不会跟踪上游每一次普通提交。