# HyperOS Media Redirect

Minimal LSPosed module for redirecting HyperOS 3 screenshot and screen recording output folders:

- `DCIM/Screenshots` -> `Pictures/Screenshots`
- `DCIM/ScreenRecorder` -> `Movies/ScreenRecorder`

## Strategy

Instead of hooking a HyperOS-specific screenshot class, this module hooks the MediaProvider insert flow and rewrites:

- `relative_path`
- `_data`
- `primary_directory`
- `secondary_directory`

That is usually more stable across SystemUI screenshots and Xiaomi screen recorder builds.

## Target processes

Enable the module for:

- `com.android.providers.media`
- `com.android.providers.media.module`

## Build

1. Open this directory in Android Studio.
2. Configure your local Android SDK.
3. Build the `app` module and install the generated APK.

If dependency download from `https://api.xposed.info/` fails, replace the dependency with a local jar:

```gradle
compileOnly files('libs/api-82.jar')
```

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
3. Select the target scopes listed above.
4. Restart the scoped processes or reboot the device.

## Verify

Take one screenshot and one screen recording, then confirm:

- screenshots are saved to `Pictures/Screenshots`
- recordings are saved to `Movies/ScreenRecorder`

Optional log filter:

```text
logcat | grep MediaPathRedirect
```

## Note

If a specific HyperOS 3 build bypasses MediaStore and writes a final file path directly inside the screenshot or recorder app, an extra hook inside that app process will be needed. This project is structured as a first-pass module around the more stable MediaProvider layer.
