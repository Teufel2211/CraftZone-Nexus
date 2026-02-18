# Release Checklist (Fabric + Forge + NeoForge)

## 1. Configure upload tokens and project IDs
- Copy `scripts/release-config.example.ps1` to `scripts/release-config.ps1`.
- Fill:
  - `MODRINTH_TOKEN`
  - `MODRINTH_PROJECT_ID` (or loader-specific overrides)
  - `CURSEFORGE_TOKEN`
  - `CURSEFORGE_PROJECT_ID` (or loader-specific overrides)

## 2. Run one command
```powershell
.\scripts\release.ps1 -ReleaseType release
```

What this does:
- Preflight build for `common`, `fabric`, `forge`, `neoforge` (abort on failure)
- Auto bump `multiloader/gradle.properties` version
- Build remapped jars for all loaders
- Generate release notes from newest commit only
- Upload all 3 loaders to Modrinth + CurseForge

## 3. Produced loader artifacts
- `multiloader/fabric/build/libs/fabric-<version>.jar`
- `multiloader/forge/build/libs/forge-<version>.jar`
- `multiloader/neoforge/build/libs/neoforge-<version>.jar`

## 4. Versioning
- Uses 10-based rollover:
  - `1.0.9 -> 1.1.0`
  - `1.9.9 -> 2.0.0`

## 5. Release notes
- Automatically generated at:
  - `multiloader/build/release-notes.md`
- Includes only newest change (`git log -n 1`).
