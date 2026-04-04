package io.github.reza.redirect;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MediaPathRedirectHook implements IXposedHookLoadPackage {
    private static final String TAG = "MediaPathRedirect";
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.providers.media",
            "com.android.providers.media.module"
    ));

    private static final String SOURCE_SCREENSHOT_DIR = "DCIM/Screenshots/";
    private static final String TARGET_SCREENSHOT_DIR = "Pictures/Screenshots/";
    private static final String SOURCE_RECORDER_DIR = "DCIM/ScreenRecorder/";
    private static final String TARGET_RECORDER_DIR = "Movies/ScreenRecorder/";

    private static final String COLUMN_DATA = "_data";
    private static final String COLUMN_OWNER_PACKAGE_NAME = "owner_package_name";
    private static final String COLUMN_PRIMARY_DIRECTORY = "primary_directory";
    private static final String COLUMN_SECONDARY_DIRECTORY = "secondary_directory";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        log("Loaded into " + lpparam.packageName + " on API " + Build.VERSION.SDK_INT);
        hookMediaProvider(lpparam.classLoader);
    }

    private void hookMediaProvider(ClassLoader classLoader) {
        Class<?> mediaProviderClass = XposedHelpers.findClassIfExists(
                "com.android.providers.media.MediaProvider",
                classLoader
        );

        if (mediaProviderClass == null) {
            log("MediaProvider class not found");
            return;
        }

        XC_MethodHook rewriteHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                ContentValues values = findFirst(param.args, ContentValues.class);
                if (values == null) {
                    return;
                }

                Uri uri = findFirst(param.args, Uri.class);
                if (!isMediaInsertUri(uri)) {
                    return;
                }

                String oldRelativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
                String oldDataPath = values.getAsString(COLUMN_DATA);

                boolean changed = rewriteForKnownFolders(values);
                if (!changed) {
                    return;
                }

                String newRelativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
                String newDataPath = values.getAsString(COLUMN_DATA);
                log("Redirected uri=" + uri
                        + " relativePath=" + safe(oldRelativePath) + " -> " + safe(newRelativePath)
                        + " data=" + safe(oldDataPath) + " -> " + safe(newDataPath));
            }
        };

        hookAllMethodsSafe(mediaProviderClass, "insert", rewriteHook);
        hookAllMethodsSafe(mediaProviderClass, "insertFile", rewriteHook);
        hookAllMethodsSafe(mediaProviderClass, "ensureFileColumns", rewriteHook);
    }

    private boolean rewriteForKnownFolders(ContentValues values) {
        String relativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
        String dataPath = values.getAsString(COLUMN_DATA);
        String primaryDirectory = values.getAsString(COLUMN_PRIMARY_DIRECTORY);
        String secondaryDirectory = values.getAsString(COLUMN_SECONDARY_DIRECTORY);
        String ownerPackageName = values.getAsString(COLUMN_OWNER_PACKAGE_NAME);
        String displayName = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);

        boolean screenshot = isScreenshotInsert(relativePath, dataPath, primaryDirectory, secondaryDirectory,
                ownerPackageName, displayName);
        boolean recorder = isRecorderInsert(relativePath, dataPath, primaryDirectory, secondaryDirectory,
                ownerPackageName, displayName);

        if (!screenshot && !recorder) {
            return false;
        }

        String targetRelativePath = screenshot ? TARGET_SCREENSHOT_DIR : TARGET_RECORDER_DIR;
        String targetPrimaryDirectory = screenshot ? "Pictures" : "Movies";
        String targetSecondaryDirectory = screenshot ? "Screenshots" : "ScreenRecorder";

        boolean changed = false;
        String rewrittenRelativePath = rewriteRelativePath(relativePath, targetRelativePath);
        if (!equalsNullable(relativePath, rewrittenRelativePath)) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, rewrittenRelativePath);
            changed = true;
        }

        String rewrittenDataPath = rewriteAbsolutePath(dataPath, screenshot);
        if (!equalsNullable(dataPath, rewrittenDataPath)) {
            values.put(COLUMN_DATA, rewrittenDataPath);
            changed = true;
        }

        if (primaryDirectory != null && !primaryDirectory.equals(targetPrimaryDirectory)) {
            values.put(COLUMN_PRIMARY_DIRECTORY, targetPrimaryDirectory);
            changed = true;
        }

        if (secondaryDirectory != null && !secondaryDirectory.equals(targetSecondaryDirectory)) {
            values.put(COLUMN_SECONDARY_DIRECTORY, targetSecondaryDirectory);
            changed = true;
        }

        return changed;
    }

    private boolean isScreenshotInsert(
            String relativePath,
            String dataPath,
            String primaryDirectory,
            String secondaryDirectory,
            String ownerPackageName,
            String displayName
    ) {
        return matchesKnownDirectory(relativePath, SOURCE_SCREENSHOT_DIR)
                || matchesDataPath(dataPath, "/DCIM/Screenshots/")
                || matchesDirectoryColumns(primaryDirectory, secondaryDirectory, "DCIM", "Screenshots")
                || belongsToScreenshotOwner(ownerPackageName, displayName);
    }

    private boolean isRecorderInsert(
            String relativePath,
            String dataPath,
            String primaryDirectory,
            String secondaryDirectory,
            String ownerPackageName,
            String displayName
    ) {
        return matchesKnownDirectory(relativePath, SOURCE_RECORDER_DIR)
                || matchesDataPath(dataPath, "/DCIM/ScreenRecorder/")
                || matchesDirectoryColumns(primaryDirectory, secondaryDirectory, "DCIM", "ScreenRecorder")
                || belongsToRecorderOwner(ownerPackageName, displayName);
    }

    private boolean belongsToScreenshotOwner(String ownerPackageName, String displayName) {
        String owner = lower(ownerPackageName);
        String name = lower(displayName);
        return owner.contains("screenshot")
                || owner.equals("com.android.systemui")
                || name.startsWith("screenshot_")
                || name.startsWith("screen_shot")
                || name.startsWith("screenshot");
    }

    private boolean belongsToRecorderOwner(String ownerPackageName, String displayName) {
        String owner = lower(ownerPackageName);
        String name = lower(displayName);
        return owner.contains("screenrecorder")
                || owner.contains("screen_recorder")
                || owner.equals("com.miui.screenrecorder")
                || name.startsWith("screenrecorder")
                || name.startsWith("screen_recorder");
    }

    private boolean matchesKnownDirectory(String relativePath, String target) {
        String normalized = normalizeRelativePath(relativePath);
        return normalized.equalsIgnoreCase(target)
                || normalized.toLowerCase(Locale.ROOT).startsWith(target.toLowerCase(Locale.ROOT));
    }

    private boolean matchesDataPath(String dataPath, String marker) {
        if (dataPath == null) {
            return false;
        }
        return dataPath.replace('\\', '/').toLowerCase(Locale.ROOT)
                .contains(marker.toLowerCase(Locale.ROOT));
    }

    private boolean matchesDirectoryColumns(String primaryDirectory, String secondaryDirectory,
                                            String expectedPrimary, String expectedSecondary) {
        return expectedPrimary.equalsIgnoreCase(safe(primaryDirectory))
                && expectedSecondary.equalsIgnoreCase(safe(secondaryDirectory));
    }

    private String rewriteRelativePath(String relativePath, String targetRelativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return targetRelativePath;
        }

        String normalized = normalizeRelativePath(relativePath);
        String lower = normalized.toLowerCase(Locale.ROOT);
        String screenshotLower = SOURCE_SCREENSHOT_DIR.toLowerCase(Locale.ROOT);
        String recorderLower = SOURCE_RECORDER_DIR.toLowerCase(Locale.ROOT);

        if (lower.startsWith(screenshotLower)) {
            return targetRelativePath + normalized.substring(SOURCE_SCREENSHOT_DIR.length());
        }
        if (lower.startsWith(recorderLower)) {
            return targetRelativePath + normalized.substring(SOURCE_RECORDER_DIR.length());
        }
        return relativePath;
    }

    private String rewriteAbsolutePath(String dataPath, boolean screenshot) {
        if (dataPath == null || dataPath.isEmpty()) {
            return dataPath;
        }

        String normalized = dataPath.replace('\\', '/');
        String source = screenshot ? "/DCIM/Screenshots/" : "/DCIM/ScreenRecorder/";
        String target = screenshot ? "/Pictures/Screenshots/" : "/Movies/ScreenRecorder/";
        String normalizedLower = normalized.toLowerCase(Locale.ROOT);
        String sourceLower = source.toLowerCase(Locale.ROOT);
        int index = normalizedLower.indexOf(sourceLower);
        if (index < 0) {
            return dataPath;
        }
        return normalized.substring(0, index) + target + normalized.substring(index + source.length());
    }

    private String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return "";
        }

        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    private boolean isMediaInsertUri(Uri uri) {
        if (uri == null) {
            return true;
        }
        String authority = uri.getAuthority();
        return authority != null && authority.contains("media");
    }

    private void hookAllMethodsSafe(Class<?> targetClass, String methodName, XC_MethodHook hook) {
        boolean found = false;
        for (Method method : targetClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                found = true;
                break;
            }
        }

        if (!found) {
            return;
        }

        try {
            XposedBridge.hookAllMethods(targetClass, methodName, hook);
            log("Hooked " + methodName);
        } catch (Throwable throwable) {
            log("Failed to hook " + methodName + ": " + throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T findFirst(Object[] args, Class<T> expectedClass) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (expectedClass.isInstance(arg)) {
                return (T) arg;
            }
        }
        return null;
    }

    private boolean equalsNullable(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private String lower(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
