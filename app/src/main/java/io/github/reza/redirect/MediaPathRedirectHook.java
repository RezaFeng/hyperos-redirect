package io.github.reza.redirect;

import android.app.Application;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import de.robv.android.xposed.AndroidAppHelper;
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
    private static final Set<String> CAMERA_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.camera",
            "com.miui.camera",
            "com.google.android.GoogleCamera",
            "org.codeaurora.snapcam",
            "com.huawei.camera",
            "com.hihonor.camera",
            "com.honor.camera",
            "com.oppo.camera",
            "com.coloros.camera",
            "com.vivo.camera",
            "com.oneplus.camera",
            "com.oplus.camera",
            "com.samsung.android.camera",
            "com.motorola.camera3",
            "com.asus.camera"
    ));

    private static final String DIRECTORY_DCIM_CAMERA = "DCIM/Camera/";
    private static final String DIRECTORY_PICTURES = "Pictures/";
    private static final String FALLBACK_APP_FOLDER = "UnknownApp";

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
                String ownerPackageName = values.getAsString(COLUMN_OWNER_PACKAGE_NAME);

                boolean changed = rewriteForStoragePolicy(values, uri);
                if (!changed) {
                    return;
                }

                String newRelativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
                String newDataPath = values.getAsString(COLUMN_DATA);
                log("Redirected owner=" + safe(ownerPackageName)
                        + " uri=" + uri
                        + " relativePath=" + safe(oldRelativePath) + " -> " + safe(newRelativePath)
                        + " data=" + safe(oldDataPath) + " -> " + safe(newDataPath));
            }
        };

        hookAllMethodsSafe(mediaProviderClass, "insert", rewriteHook);
        hookAllMethodsSafe(mediaProviderClass, "insertFile", rewriteHook);
        hookAllMethodsSafe(mediaProviderClass, "ensureFileColumns", rewriteHook);
    }

    private boolean rewriteForStoragePolicy(ContentValues values, Uri uri) {
        String relativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
        String dataPath = values.getAsString(COLUMN_DATA);
        String primaryDirectory = values.getAsString(COLUMN_PRIMARY_DIRECTORY);
        String secondaryDirectory = values.getAsString(COLUMN_SECONDARY_DIRECTORY);
        String ownerPackageName = values.getAsString(COLUMN_OWNER_PACKAGE_NAME);

        boolean ownerIsCameraApp = isCameraApp(ownerPackageName);
        boolean imageLikeMedia = isImageLikeMedia(values, uri);
        boolean targetsDcim = targetsDcim(relativePath, dataPath, primaryDirectory);

        TargetPath targetPath;
        if (ownerIsCameraApp && imageLikeMedia) {
            targetPath = new TargetPath(DIRECTORY_DCIM_CAMERA, "DCIM", "Camera");
        } else if (targetsDcim) {
            String appFolderName = resolveAppFolderName(ownerPackageName);
            targetPath = new TargetPath(DIRECTORY_PICTURES + appFolderName + "/", "Pictures", appFolderName);
        } else {
            return false;
        }

        boolean changed = false;
        String rewrittenRelativePath = rewriteRelativePath(relativePath, targetPath.relativePath, targetsDcim);
        if (!equalsNullable(relativePath, rewrittenRelativePath)) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, rewrittenRelativePath);
            changed = true;
        }

        String rewrittenDataPath = rewriteAbsolutePath(dataPath, targetPath.relativePath);
        if (!equalsNullable(dataPath, rewrittenDataPath)) {
            values.put(COLUMN_DATA, rewrittenDataPath);
            changed = true;
        }

        if (!equalsNullable(primaryDirectory, targetPath.primaryDirectory)) {
            values.put(COLUMN_PRIMARY_DIRECTORY, targetPath.primaryDirectory);
            changed = true;
        }

        if (!equalsNullable(secondaryDirectory, targetPath.secondaryDirectory)) {
            values.put(COLUMN_SECONDARY_DIRECTORY, targetPath.secondaryDirectory);
            changed = true;
        }

        return changed;
    }

    private boolean isCameraApp(String ownerPackageName) {
        if (ownerPackageName == null || ownerPackageName.isEmpty()) {
            return false;
        }

        if (CAMERA_PACKAGES.contains(ownerPackageName)) {
            return true;
        }

        String lower = ownerPackageName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".camera")
                || lower.contains(".camera.")
                || lower.contains("googlecamera")
                || lower.contains("gcam")
                || lower.contains("snapcam");
    }

    private boolean isImageLikeMedia(ContentValues values, Uri uri) {
        String mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE);
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }

        String displayName = lower(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        return displayName.endsWith(".jpg")
                || displayName.endsWith(".jpeg")
                || displayName.endsWith(".png")
                || displayName.endsWith(".webp")
                || displayName.endsWith(".bmp")
                || displayName.endsWith(".heic")
                || displayName.endsWith(".heif")
                || displayName.endsWith(".dng")
                || uriLooksLikeImage(uri);
    }

    private boolean uriLooksLikeImage(Uri uri) {
        if (uri == null) {
            return false;
        }
        String text = lower(uri.toString());
        return text.contains("/images/")
                || text.endsWith("/images/media")
                || text.contains("image");
    }

    private boolean targetsDcim(String relativePath, String dataPath, String primaryDirectory) {
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        if (normalizedRelativePath.toLowerCase(Locale.ROOT).startsWith("dcim/")) {
            return true;
        }

        if ("DCIM".equalsIgnoreCase(safe(primaryDirectory))) {
            return true;
        }

        return matchesDataPath(dataPath, "/DCIM/");
    }

    private String resolveAppFolderName(String ownerPackageName) {
        String appLabel = resolveApplicationLabel(ownerPackageName);
        String folderName = sanitizeFolderName(appLabel);
        if (!folderName.isEmpty()) {
            return folderName;
        }

        folderName = sanitizeFolderName(ownerPackageName);
        if (!folderName.isEmpty()) {
            return folderName;
        }

        return FALLBACK_APP_FOLDER;
    }

    private String resolveApplicationLabel(String ownerPackageName) {
        if (ownerPackageName == null || ownerPackageName.isEmpty()) {
            return "";
        }

        try {
            Application application = AndroidAppHelper.currentApplication();
            if (application == null) {
                return "";
            }

            PackageManager packageManager = application.getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(ownerPackageName, 0);
            CharSequence label = packageManager.getApplicationLabel(applicationInfo);
            return label == null ? "" : label.toString();
        } catch (Throwable throwable) {
            log("Unable to resolve label for " + ownerPackageName + ": " + throwable);
            return "";
        }
    }

    private String sanitizeFolderName(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\' || current == '/' || current == ':' || current == '*'
                    || current == '?' || current == '"' || current == '<'
                    || current == '>' || current == '|') {
                continue;
            }
            if (Character.isISOControl(current)) {
                continue;
            }
            builder.append(current);
        }

        String sanitized = builder.toString().trim();
        if (sanitized.isEmpty()) {
            return "";
        }
        return sanitized.replaceAll("\\s+", " ");
    }

    private boolean matchesDataPath(String dataPath, String marker) {
        if (dataPath == null) {
            return false;
        }
        return dataPath.replace('\\', '/').toLowerCase(Locale.ROOT)
                .contains(marker.toLowerCase(Locale.ROOT));
    }

    private String rewriteRelativePath(String relativePath, String targetRelativePath, boolean replaceDcimPrefix) {
        String normalizedTarget = normalizeRelativePath(targetRelativePath);
        if (relativePath == null || relativePath.isEmpty()) {
            return normalizedTarget;
        }

        String normalized = normalizeRelativePath(relativePath);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (replaceDcimPrefix && lower.startsWith("dcim/")) {
            return normalizedTarget;
        }
        return normalizedTarget;
    }

    private String rewriteAbsolutePath(String dataPath, String targetRelativePath) {
        if (dataPath == null || dataPath.isEmpty()) {
            return dataPath;
        }

        String normalized = dataPath.replace('\\', '/');
        int storageIndex = normalized.toLowerCase(Locale.ROOT).indexOf("/storage/");
        int emulatedIndex = normalized.toLowerCase(Locale.ROOT).indexOf("/emulated/");
        int rootIndex = storageIndex >= 0 ? storageIndex : emulatedIndex;
        if (rootIndex < 0) {
            return dataPath;
        }

        int directorySplit = normalized.lastIndexOf('/');
        if (directorySplit < 0 || directorySplit == normalized.length() - 1) {
            return dataPath;
        }

        int afterVolume = findAfterVolumeIndex(normalized, rootIndex);
        if (afterVolume < 0 || afterVolume > directorySplit) {
            return dataPath;
        }

        String prefix = normalized.substring(0, afterVolume);
        String fileName = normalized.substring(directorySplit + 1);
        return prefix + normalizeRelativePath(targetRelativePath) + fileName;
    }

    private int findAfterVolumeIndex(String absolutePath, int rootIndex) {
        int firstSlash = absolutePath.indexOf('/', rootIndex + 1);
        if (firstSlash < 0) {
            return -1;
        }
        int secondSlash = absolutePath.indexOf('/', firstSlash + 1);
        if (secondSlash < 0) {
            return -1;
        }
        int thirdSlash = absolutePath.indexOf('/', secondSlash + 1);
        if (thirdSlash < 0) {
            return -1;
        }
        return thirdSlash + 1;
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

    private static final class TargetPath {
        final String relativePath;
        final String primaryDirectory;
        final String secondaryDirectory;

        TargetPath(String relativePath, String primaryDirectory, String secondaryDirectory) {
            this.relativePath = relativePath;
            this.primaryDirectory = primaryDirectory;
            this.secondaryDirectory = secondaryDirectory;
        }
    }
}
