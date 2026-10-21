package com.example.devicefinder;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 默默拉起后台守护服务
        Intent intent = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        // 2. 如果是通过点击桌面图标打开的，给个友好提示避免误以为闪退
        if (getIntent() != null && getIntent().getCategories() != null 
                && getIntent().getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
            Toast.makeText(this, "🛡️ 备用机守护系统运行中", Toast.LENGTH_SHORT).show();
        }

        // 3. 瞬间自我隐身关闭
        finish();
    }
}
