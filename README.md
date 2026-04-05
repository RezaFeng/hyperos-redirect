# HyperOS Media Redirect

LSPosed module for rewriting MediaStore save paths on HyperOS.

## Current policy

- The policy only applies to apps that are explicitly selected in LSPosed scope
- Apps not selected in LSPosed keep their default media save paths
- Camera apps are allowed to save photos into `DCIM/Camera`
- Any other app is not allowed to save into `DCIM/*`
- When a non-camera app targets `DCIM/*`, the path is rewritten by media type:
- images -> `Pictures/<AppName>/`
- videos -> `Movies/<AppName>/`
- audio -> `Music/<AppName>/`
- other media -> `Pictures/<AppName>/`
- `<AppName>` uses the resolved application label first, then falls back to the package name

## Notes about matching

- Camera app detection uses a built-in allowlist plus package-name heuristics
- Media type detection prefers MIME type, then file extension, then MediaStore URI shape
- If the owner package name cannot be resolved, the module skips rewriting instead of forcing `UnknownApp`

## Strategy

The module hooks `ContentResolver.insert(...)` inside each LSPosed-scoped app process and rewrites only:

- `relative_path`

This means LSPosed scope now directly controls which apps are affected. No global provider-side interception is used.

## GitHub Actions build

This repository includes a workflow at `.github/workflows/build-debug-apk.yml`.

After pushing the project to GitHub:

1. Open the repository `Actions` tab.
2. Run `Build Debug APK`, or trigger it with a push.
3. Wait for the job to finish.
4. Download the artifact named `hyperos-media-redirect-debug-apk`.
5. The APK file inside the artifact is named `hyperos-media-redirect-debug.apk`.

The repository now uses a persistent keystore file at `.signing/hyperos-redirect.jks` for stable CI signing. The workflow will create and commit it on the first run if it does not already exist.

## GitHub Release

The repository also includes `.github/workflows/release.yml`.

Push a tag such as `v1.3.0` to build a release APK and publish a GitHub Release asset:

```text
git tag v1.3.0
git push origin v1.3.0
```

## LSPosed setup

1. Install the APK.
2. Enable the module in LSPosed.
3. In scope, select only the apps you want to redirect.
4. Do not rely on `com.android.providers.media` scope anymore.
5. Restart the selected apps or reboot the device.

## Verify

Check at least these cases:

- a scoped camera app still saves photos to `DCIM/Camera`
- a scoped screenshot or recording app is redirected by the policy
- a non-scoped app keeps its default save path
- any scoped third-party app trying to write into `DCIM/*` is redirected out of `DCIM`

Optional log filter:

```text
logcat | grep MediaPathRedirect
```

## Important assumption

This version treats the new policy as overriding the earlier fixed screenshot and screen recorder folder rules. It also moves enforcement from the provider side to the scoped app side so LSPosed scope is the real allowlist.

## Signing note

Stable signing starts from the workflow revision that uses the repository keystore `.signing/hyperos-redirect.jks`.

If you already installed an older APK built before the repository keystore existed, Android may still refuse an in-place upgrade because that older private key was never persisted. In that case, uninstall once, then install the new APK. After that, future CI builds and releases should remain updatable with the same signature because the repository keystore is stable.
