---
applyTo: "scripts/*.sh"
description: "Use when modifying the Android Tavern build pipeline shell scripts. Read the target script's Build Plan Contract or Stage Contract block first and preserve the documented stage boundary unless the user explicitly asks to change it."
---

# Tavern Build Stage Boundaries

- Treat the contract block at the top of the target script as authoritative.
- `scripts/resolve-tavern-build-plan.sh` computes versions, changed flags, release naming, and canonical output paths only.
- `scripts/build-tavern-android-runtime-image.sh` is stage 1 only and may output only runtime image/rootfs artifacts.
- `scripts/build-tavern-dependency-packs.sh` is stage 2 only and may output only dependency pack archives/manifests.
- `scripts/sync-tavern-android-bootstrap.sh` is stage 3 only and may output only `server-source.zip` plus its manifest.
- `scripts/build-tavern-android-apk.sh` is stage 4 only and is the only script allowed to copy server source, dependency packs, host scripts, and patch assets into the APK asset tree.
- `scripts/build-tavern-android-local.sh` is the normal one-click local entrypoint and must orchestrate the same stages and paths as CI instead of reimplementing them.
- If a request changes stage boundaries, update the relevant script contract header and the README four-stage-boundary section in the same change.
