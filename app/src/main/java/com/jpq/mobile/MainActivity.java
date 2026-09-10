package com.jpq.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Transparent entry point for system consent and the two identity fields. */
public final class MainActivity extends Activity {
    private static final int OVERLAY = 79, NOTIFICATIONS = 80, CAPTURE = 81;
    private IdentitySettingsDialog settingsDialog;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved == null || getIntent().getBooleanExtra("hand_settings", false)) route();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        route();
    }

    @Override protected void onDestroy() {
        if (settingsDialog != null) settingsDialog.close();
        super.onDestroy();
    }

    private void route() {
        if (getIntent().getBooleanExtra("hand_settings", false)) {
            showSettings();
            return;
        }
        CaptureService service = CaptureService.current;
        if (service != null) {
            service.expandControls();
            finish();
        } else if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), OVERLAY);
        } else requestCapture();
    }

    private void requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else projectionPrompt();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == NOTIFICATIONS) projectionPrompt();
    }

    private void projectionPrompt() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(consent, CAPTURE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY) {
            if (Settings.canDrawOverlays(this)) requestCapture();
            else { toast("需要允许悬浮窗才能显示控制栏"); finish(); }
        } else if (request == CAPTURE) {
            if (result == RESULT_OK && data != null) {
                getSharedPreferences("capture", 0).edit().remove("last_error").apply();
                startForegroundService(new Intent(this, CaptureService.class)
                        .putExtra("projection", data).putExtra("result", result));
            }
            finish();
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void showSettings() {
        CaptureService service=CaptureService.current;
        if(service!=null){service.showIdentitySettings();finish();return;}
        if(settingsDialog!=null&&settingsDialog.isShowing())return;
        if(!Settings.canDrawOverlays(this)){routeWithoutSettings();return;}
        settingsDialog=new IdentitySettingsDialog(this,null,()->{},this::finish);
    }
    private void routeWithoutSettings(){getIntent().removeExtra("hand_settings");route();}
}
