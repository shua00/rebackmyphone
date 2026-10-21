package com.example.devicefinder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView statusTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 用代码动态构建一个清爽的调试界面，不需要写 res/layout XML
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 80, 50, 50);

        statusTextView = new TextView(this);
        statusTextView.setText("🛡️ 备用机守护服务运行中\n\n状态：等待权限与网络连接...");
        statusTextView.setTextSize(16f);
        layout.addView(statusTextView);

        // 添加一个“手动立即上报”按钮，方便联调测试
        Button testButton = new Button(this);
        testButton.setText("立即触发一次上报测试");
        testButton.setPadding(20, 20, 20, 20);
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 发送广播或直接通过 Intent 触发 Service 行为
                Toast.makeText(MainActivity.this, "已尝试触发上报，请检查电脑控制台！", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(MainActivity.this, TrackingService.class);
                intent.setAction("ACTION_MANUAL_REPORT");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            }
        });
        
        // 留出一点间距
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = 50;
        testButton.setLayoutParams(params);
        layout.addView(testButton);

        setContentView(layout);

        // 请求定位权限
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, 101);
                return;
            }
        }
        startGuardianService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startGuardianService();
    }

    private void startGuardianService() {
        Intent intent = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusTextView.setText("🛡️ 备用机守护服务运行中\n\n状态：后台定位与上报已激活\n\n提示：请确保电脑 Python 服务端 IP 地址已正确填入代码中。");
    }
}
