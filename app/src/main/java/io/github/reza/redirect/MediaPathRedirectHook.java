package io.github.reza.redirect;

import android.app.AndroidAppHelper;
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
    private static final String DIRECTORY_MOVIES = "Movies/";
    private static final String DIRECTORY_MUSIC = "Music/";
    private static final String FALLBACK_APP_FOLDER = "UnknownApp";

    private static final String COLUMN_OWNER_PACKAGE_NAME = "owner_package_name";

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
                String ownerPackageName = resolveOwnerPackageName(param.thisObject, values);

                boolean changed = rewriteForStoragePolicy(values, uri, ownerPackageName);
                if (!changed) {
                    return;
                }

                String newRelativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
                log("Redirected owner=" + safe(ownerPackageName)
                        + " uri=" + uri
                        + " relativePath=" + safe(oldRelativePath) + " -> " + safe(newRelativePath));
            }
        };

        hookAllMethodsSafe(mediaProviderClass, "ensureFileColumns", rewriteHook);
    }

    private boolean rewriteForStoragePolicy(ContentValues values, Uri uri, String ownerPackageName) {
        String relativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);

        boolean ownerIsCameraApp = isCameraApp(ownerPackageName);
        MediaKind mediaKind = resolveMediaKind(values, uri);
        boolean targetsDcim = targetsDcim(relativePath);

        if (ownerPackageName.isEmpty()) {
            return false;
        }

        String targetRelativePath;
        if (ownerIsCameraApp && mediaKind == MediaKind.IMAGE) {
            targetRelativePath = DIRECTORY_DCIM_CAMERA;
        } else if (targetsDcim) {
            String appFolderName = resolveAppFolderName(ownerPackageName);
            targetRelativePath = buildAppScopedTargetRelativePath(mediaKind, appFolderName);
        } else {
            return false;
        }

        String rewrittenRelativePath = rewriteRelativePath(relativePath, targetRelativePath);
        if (equalsNullable(relativePath, rewrittenRelativePath)) {
            return false;
        }
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, rewrittenRelativePath);
        return true;
    }

    private String resolveOwnerPackageName(Object mediaProvider, ContentValues values) {
        String ownerPackageName = values.getAsString(COLUMN_OWNER_PACKAGE_NAME);
        if (ownerPackageName != null && !ownerPackageName.isEmpty()) {
            return ownerPackageName;
        }

        try {
            Object callingPackage = XposedHelpers.callMethod(mediaProvider, "getCallingPackageUnchecked");
            if (callingPackage instanceof String) {
                return (String) callingPackage;
            }
        } catch (Throwable throwable) {
            log("Unable to resolve calling package: " + throwable);
        }

        return "";
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

    private MediaKind resolveMediaKind(ContentValues values, Uri uri) {
        String mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE);
        if (mimeType != null) {
            String lowerMimeType = mimeType.toLowerCase(Locale.ROOT);
            if (lowerMimeType.startsWith("image/")) {
                return MediaKind.IMAGE;
            }
            if (lowerMimeType.startsWith("video/")) {
                return MediaKind.VIDEO;
            }
            if (lowerMimeType.startsWith("audio/")) {
                return MediaKind.AUDIO;
            }
        }

        String displayName = lower(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        if (displayName.endsWith(".jpg")
                || displayName.endsWith(".jpeg")
                || displayName.endsWith(".png")
                || displayName.endsWith(".webp")
                || displayName.endsWith(".bmp")
                || displayName.endsWith(".heic")
                || displayName.endsWith(".heif")
                || displayName.endsWith(".dng")
                || uriLooksLikeImage(uri)) {
            return MediaKind.IMAGE;
        }
        if (displayName.endsWith(".mp4")
                || displayName.endsWith(".mkv")
                || displayName.endsWith(".webm")
                || displayName.endsWith(".3gp")
                || uriLooksLikeVideo(uri)) {
            return MediaKind.VIDEO;
        }
        if (displayName.endsWith(".mp3")
                || displayName.endsWith(".aac")
                || displayName.endsWith(".wav")
                || displayName.endsWith(".m4a")) {
            return MediaKind.AUDIO;
        }
        return MediaKind.OTHER;
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

    private boolean uriLooksLikeVideo(Uri uri) {
        if (uri == null) {
            return false;
        }
        String text = lower(uri.toString());
        return text.contains("/video/")
                || text.endsWith("/video/media")
                || text.contains("video");
    }

    private boolean targetsDcim(String relativePath) {
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        return normalizedRelativePath.toLowerCase(Locale.ROOT).startsWith("dcim/");
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

    private String buildAppScopedTargetRelativePath(MediaKind mediaKind, String appFolderName) {
        switch (mediaKind) {
            case VIDEO:
                return DIRECTORY_MOVIES + appFolderName + "/";
            case AUDIO:
                return DIRECTORY_MUSIC + appFolderName + "/";
            case IMAGE:
            case OTHER:
            default:
                return DIRECTORY_PICTURES + appFolderName + "/";
        }
    }

    private String rewriteRelativePath(String relativePath, String targetRelativePath) {
        String normalizedTarget = normalizeRelativePath(targetRelativePath);
        if (relativePath == null || relativePath.isEmpty()) {
            return normalizedTarget;
        }

        return normalizedTarget;
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

    private enum MediaKind {
        IMAGE,
        VIDEO,
        AUDIO,
        OTHER
    }
}
