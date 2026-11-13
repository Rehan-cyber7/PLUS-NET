# Releasing PlusNet

The `Build PlusNet Android APK` workflow builds the Android debug APK on GitHub-hosted runners. Pushing a version tag matching `v*` also publishes or updates a GitHub Release with the APK and SHA-256 checksum.

## Current release

- Root app version: `0.4.2`
- Native plugin version: `0.4.2`
- Android versionCode: `7`
- Android application ID: `app.plusnet.mobile`
- Git tag: `v0.4.2`
- Release title: `PlusNet v0.4.2`

## Publish the release

Commit and push the release files first:

```bash
git add .
git commit -m "Prepare PlusNet v0.4.0 release"
git push
```

Create and push the annotated tag:

```bash
git tag -a v0.4.0 -m "PlusNet v0.4.0"
git push origin v0.4.0
```

Then monitor **GitHub → Actions**. On success, open **GitHub → Releases**. The release contains:

- `PlusNet-0.4.0-debug.apk`
- `PlusNet-0.4.0-SHA256SUMS.txt`

Do not reuse a published tag or Android versionCode. The tag must match the root package version exactly.

## Next patch release example

For `0.4.1`:

1. Update `package.json` version to `0.4.1`.
2. Update `plugins/plusnet-engine/package.json` version to `0.4.1`.
3. Increase `ANDROID_VERSION_CODE` from `5` to `6` in `.github/workflows/android-apk.yml`.
4. Update `RELEASE_NOTES.md`.
5. Commit and push the changes.
6. Create and push tag `v0.4.1`.

```bash
git add package.json plugins/plusnet-engine/package.json \
  .github/workflows/android-apk.yml RELEASE_NOTES.md
git commit -m "Prepare PlusNet v0.4.1 release"
git push
git tag -a v0.4.1 -m "PlusNet v0.4.1"
git push origin v0.4.1
```

## Android application identity

The rebrand uses application ID `app.plusnet.mobile`. It is distinct from the old `app.plexo.mobile` package, so the first PlusNet installation is separate from the old Plexo installation. Keep `app.plusnet.mobile` stable for all future PlusNet updates.
