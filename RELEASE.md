# Release Checklist (Modrinth + CurseForge)

## 1. Update version
- Edit `gradle.properties`:
  - `mod_version=...`

## 2. Update changelog
- Add new section in `CHANGELOG.md` with release date and changes.

## 3. Build artifacts
```powershell
./gradlew clean build
```

Artifacts:
- `build/libs/core-mod-<version>.jar` (main)
- `build/libs/core-mod-<version>-sources.jar` (sources)

## 4. Modrinth upload
- Project: `core` (or your existing slug)
- Loader: `fabric`
- Game version: `1.21.11`
- Java: `21`
- Dependencies:
  - `fabric-api` (required)
  - `voicechat` (required, if kept in depends)
- Changelog: copy from `CHANGELOG.md`
- Primary file: remapped main jar from `build/libs`

## 5. CurseForge upload
- Game version: `1.21.11`
- Mod loader: `Fabric`
- Java: `21`
- Relations:
  - Fabric API: Required
  - Simple Voice Chat: Required (if applicable)
- Changelog: copy from `CHANGELOG.md`
- Upload the same main jar.

## 6. Sanity checks
- Start dedicated server with only required dependencies.
- Confirm no missing icon/metadata warnings.
- Confirm key commands:
  - `/shop`
  - `/shopadmin audit`
  - `/shopadmin recategorize`
