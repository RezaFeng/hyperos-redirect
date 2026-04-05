# HyperOS Media Redirect

LSPosed module for rewriting MediaStore save paths on HyperOS.

## Current policy

- Camera apps are allowed to save photos into `DCIM/Camera`
- Any other app is not allowed to save into `DCIM/*`
- When a non-camera app targets `DCIM/*`, the path is rewritten to `Pictures/<AppName>/`
- `<AppName>` uses the resolved application label first, then falls back to the package name

## Notes about matching

- Camera app detection uses a built-in allowlist plus package-name heuristics
- "Photo" detection prefers MIME type, then file extension, then MediaStore image URIs
- If the owner package name cannot be resolved, the module falls back to `Pictures/UnknownApp/`

## Strategy

The module hooks the MediaProvider insert flow and rewrites:

- `relative_path`
- `_data`
- `primary_directory`
- `secondary_directory`

This is usually more stable than hooking a specific HyperOS screenshot or recorder implementation.

## Target processes

Enable the module for:

- `com.android.providers.media`
- `com.android.providers.media.module`

## GitHub Actions build

This repository includes a workflow at `.github/workflows/build-debug-apk.yml`.

After pushing the project to GitHub:

1. Open the repository `Actions` tab.
2. Run `Build Debug APK`, or trigger it with a push.
3. Wait for the job to finish.
4. Download the artifact named `hyperos-media-redirect-debug-apk`.
5. The APK file inside the artifact is named `hyperos-media-redirect-debug.apk`.

## LSPosed setup

1. Install the APK.
2. Enable the module in LSPosed.
3. Keep the recommended scopes selected.
4. Restart the scoped processes or reboot the device.

## Verify

Check at least these cases:

- camera photos still save to `DCIM/Camera`
- screenshots from other apps move under `Pictures/<AppName>/`
- recordings from other apps move under `Pictures/<AppName>/`
- any third-party app trying to write into `DCIM/*` is redirected out of `DCIM`

Optional log filter:

```text
logcat | grep MediaPathRedirect
```

## Important assumption

This version treats the new policy as overriding the earlier fixed screenshot and screen recorder folder rules. If you want screenshots to remain in a shared `Pictures/Screenshots` folder while only other apps are grouped by app name, that is a different rule set and should be implemented explicitly.
