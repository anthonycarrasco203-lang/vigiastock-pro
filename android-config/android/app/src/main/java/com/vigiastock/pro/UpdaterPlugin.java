package com.vigiastock.pro;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "VigiaUpdater")
public class UpdaterPlugin extends Plugin {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @PluginMethod
    public void getAppInfo(PluginCall call) {
        try {
            PackageInfo info = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            JSObject result = new JSObject();
            result.put("versionCode", versionCode);
            result.put("versionName", info.versionName == null ? "0.0.0" : info.versionName);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("No se pudo leer la versión instalada: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void downloadAndInstall(PluginCall call) {
        final String url = call.getString("url", "");
        final String expectedSha = call.getString("sha256", "").replace("sha256:", "").trim().toLowerCase(Locale.ROOT);
        final String accessPassword = call.getString("accessPassword", "");

        if (!url.startsWith("https://")) {
            call.reject("La descarga debe utilizar HTTPS.");
            return;
        }
        if (!expectedSha.matches("[a-f0-9]{64}")) {
            call.reject("El SHA-256 de la actualización no es válido.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getContext().getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(settings);
                call.reject("Autoriza a VigíaStock Pro a instalar aplicaciones y vuelve a pulsar Actualizar ahora.",
                        "INSTALL_PERMISSION_REQUIRED");
            } catch (Exception e) {
                call.reject("No se pudo abrir el permiso para instalar actualizaciones: " + e.getMessage(), e);
            }
            return;
        }

        EXECUTOR.execute(() -> {
            File apk = null;
            try {
                File folder = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (folder == null) throw new Exception("Almacenamiento privado de la aplicación no disponible.");
                if (!folder.exists() && !folder.mkdirs()) throw new Exception("No se pudo crear la carpeta de descarga.");
                apk = new File(folder, "VigiaStock-Pro-update.apk");
                File partial = new File(folder, "VigiaStock-Pro-update.apk.part");

                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(90000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
                if (!accessPassword.isEmpty()) connection.setRequestProperty("X-Update-Password", accessPassword);
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    connection.disconnect();
                    throw new Exception("El servidor de actualizaciones respondió " + status + ".");
                }

                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.flush();
                } finally {
                    connection.disconnect();
                }

                String actualSha = sha256(partial);
                if (!expectedSha.equals(actualSha)) {
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                    throw new Exception("La verificación SHA-256 falló. No se instalará este archivo.");
                }
                if (apk.exists() && !apk.delete()) throw new Exception("No se pudo reemplazar el APK descargado anteriormente.");
                if (!partial.renameTo(apk)) throw new Exception("No se pudo finalizar el archivo APK.");

                Uri apkUri = FileProvider.getUriForFile(getContext(),
                        getContext().getPackageName() + ".fileprovider", apk);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(apkUri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().runOnUiThread(() -> {
                    try {
                        getActivity().startActivity(install);
                        JSObject result = new JSObject();
                        result.put("verified", true);
                        result.put("sha256", actualSha);
                        call.resolve(result);
                    } catch (Exception e) {
                        call.reject("El APK se descargó, pero Android no pudo abrir el instalador: " + e.getMessage(), e);
                    }
                });
            } catch (Exception e) {
                call.reject("No se pudo descargar la actualización: " + e.getMessage(), e);
            }
        });
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        byte[] bytes = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }
}
