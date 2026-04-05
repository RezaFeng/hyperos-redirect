package io.github.reza.redirect;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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

    private static final String MODULE_PACKAGE = "io.github.reza.redirect";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null || lpparam.packageName.isEmpty()) {
            return;
        }
        if ("android".equals(lpparam.packageName) || MODULE_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        log("Loaded into " + lpparam.packageName + " process=" + lpparam.processName + " api=" + Build.VERSION.SDK_INT);
        hookContentResolverInsert(lpparam.classLoader, lpparam.packageName);
    }

    private void hookContentResolverInsert(ClassLoader classLoader, final String packageName) {
        Class<?> contentResolverClass = XposedHelpers.findClassIfExists(ContentResolver.class.getName(), classLoader);
        if (contentResolverClass == null) {
            log("ContentResolver class not found for " + packageName);
            return;
        }

        XC_MethodHook rewriteHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Uri uri = findFirst(param.args, Uri.class);
                if (!isMediaInsertUri(uri)) {
                    return;
                }

                ContentValues values = findFirst(param.args, ContentValues.class);
                if (values == null) {
                    return;
                }

                String oldRelativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
                if (oldRelativePath == null || oldRelativePath.isEmpty()) {
                    return;
                }

                boolean changed = rewriteForStoragePolicy(values, uri, packageName);
                if (!changed) {
                    return;
                }

                String newRelativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
                log("Redirected package=" + packageName
                        + " uri=" + uri
                        + " relativePath=" + safe(oldRelativePath) + " -> " + safe(newRelativePath));
            }
        };

        try {
            XposedHelpers.findAndHookMethod(
                    contentResolverClass,
                    "insert",
                    Uri.class,
                    ContentValues.class,
                    rewriteHook
            );
            log("Hooked ContentResolver.insert(Uri, ContentValues) for " + packageName);
        } catch (Throwable throwable) {
            log("Failed hook insert(Uri, ContentValues) for " + packageName + ": " + throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    contentResolverClass,
                    "insert",
                    Uri.class,
                    ContentValues.class,
                    Bundle.class,
                    rewriteHook
            );
            log("Hooked ContentResolver.insert(Uri, ContentValues, Bundle) for " + packageName);
        } catch (Throwable throwable) {
            log("Failed hook insert(Uri, ContentValues, Bundle) for " + packageName + ": " + throwable);
        }
    }

    private boolean rewriteForStoragePolicy(ContentValues values, Uri uri, String packageName) {
        String relativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
        if (!targetsDcim(relativePath)) {
            return false;
        }

        MediaKind mediaKind = resolveMediaKind(values, uri);
        String targetRelativePath;
        if (isCameraApp(packageName) && mediaKind == MediaKind.IMAGE) {
            targetRelativePath = DIRECTORY_DCIM_CAMERA;
        } else {
            String appFolderName = resolveAppFolderName(packageName);
            targetRelativePath = buildAppScopedTargetRelativePath(mediaKind, appFolderName);
        }

        String rewrittenRelativePath = normalizeRelativePath(targetRelativePath);
        if (equalsNullable(relativePath, rewrittenRelativePath)) {
            return false;
        }
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, rewrittenRelativePath);
        return true;
    }

    private boolean isCameraApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        if (CAMERA_PACKAGES.contains(packageName)) {
            return true;
        }

        String lower = packageName.toLowerCase(Locale.ROOT);
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
        return normalizeRelativePath(relativePath).toLowerCase(Locale.ROOT).startsWith("dcim/");
    }

    private String resolveAppFolderName(String packageName) {
        String appLabel = resolveApplicationLabel(packageName);
        String folderName = sanitizeFolderName(appLabel);
        if (!folderName.isEmpty()) {
            return folderName;
        }

        folderName = sanitizeFolderName(packageName);
        if (!folderName.isEmpty()) {
            return folderName;
        }

        return FALLBACK_APP_FOLDER;
    }

    private String resolveApplicationLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "";
        }

        try {
            Application application = AndroidAppHelper.currentApplication();
            if (application == null) {
                return "";
            }

            PackageManager packageManager = application.getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(applicationInfo);
            return label == null ? "" : label.toString();
        } catch (Throwable throwable) {
            log("Unable to resolve label for " + packageName + ": " + throwable);
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

    private boolean isMediaInsertUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        String authority = uri.getAuthority();
        return "content".equalsIgnoreCase(safe(scheme))
                && authority != null
                && authority.contains("media");
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
