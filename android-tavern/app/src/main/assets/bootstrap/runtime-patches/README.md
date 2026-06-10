# SillyDroid Runtime Patches

This directory contains the optional SillyDroid runtime patch framework for the bundled SillyTavern server.

The framework is intentionally separate from the Stage 3 server source artifact:

- Stage 3 still packages the upstream SillyTavern source without applying these patches.
- The Android host enables this framework only when the user turns on the experimental performance switch.
- `loader.cjs` is loaded through Node `--require` before `server.js`.
- Each feature patch must live in its own module directory and declare support metadata in `manifest.json`.
- Module metadata is schema-driven. Add module-level `title`, `version`, `description`, and `supportedTavernVersions`; add `settings[]` entries with `key`, `type`, `title`, `description`, `defaultValue`, `version`, `restartRequired`, optional `options` / `min` / `max` / `unit`, and `important=true` for values that should appear in the patch list. The Android settings page renders them automatically and the loader passes validated values to `apply({ settings })`.

当前 `performance` 预设包含 `character-all-limited-concurrency` 模块，只对 SillyTavern 1.18.0 的 `/api/characters/all` 角色卡批量处理做运行时并发限制。后续新增魔改功能时必须新增独立模块，不能继续把逻辑堆进 `loader.cjs`。

升级内置 SillyTavern 时必须先验证 patch 兼容性，再更新 root manifest 的 `compatibleTavernVersions` 和模块 manifest 的 `supportedTavernVersions`。版本不匹配时 loader 会记录 `patch_effective=false reason=unsupported_tavern_version` 并跳过加载，避免把新酒馆启动弄崩。
