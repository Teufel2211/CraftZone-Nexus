# Core Mod Multi-Loader Scaffold

This folder contains a clean Architectury scaffold to target:

- Fabric
- Forge
- NeoForge

across multiple Minecraft versions (1.19.x -> 1.21.x), with per-version build lanes.

## Important

This is a scaffold and migration target.  
Your existing mod code in `src/main/java/core/**` is not auto-ported yet.

Migration status:
- Phase 1 started: shared `Safe` and `ConfigManager` moved to `common`.
- Next: move logging/dashboard/discord core service layer into `common`.

## Structure

- `common` shared code
- `fabric` Fabric loader entry
- `forge` Forge loader entry
- `neoforge` NeoForge loader entry

## Commands

Run from `multiloader/`:

```bash
./gradlew :fabric:build
./gradlew :forge:build
./gradlew :neoforge:build
```

## Version lanes

Use independent branches or CI matrix for:

- `mc-1.19.x`
- `mc-1.20.x`
- `mc-1.21.x`

Each lane should pin:

- Minecraft version
- Loader versions
- Mapping version

in `multiloader/gradle.properties`.
