package com.example.devicefinder;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class DeviceInfoUtils {

    // 全局内存缓存：用来对抗 Android 10+ 后台无法直接读取 Wi-Fi SSID 的限制
    public static String cachedSsid = "UNKNOWN";
    public static String cachedBssid = "UNKNOWN";

    // 获取电量信息 [电量百分比, 是否在充电(1/0)]
    public static int[] getBatteryInfo(Context context) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        int batteryPct = (int) ((level / (float) scale) * 100);

        int status = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
        int isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                          status == BatteryManager.BATTERY_STATUS_FULL) ? 1 : 0;

        return new int[]{batteryPct, isCharging};
    }

    // 获取 Wi-Fi 信息 [SSID, BSSID]（带内存缓存降级）
    public static String[] getWifiInfo(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return new String[]{cachedSsid, cachedBssid};
        
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            String ssid = info != null ? info.getSSID() : "UNKNOWN";
            
            // 如果成功拿到了非匿名的真实 SSID，立刻更新全局缓存
            if (ssid != null && !ssid.equals("<unknown ssid>") && !ssid.equals("UNKNOWN")) {
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                cachedSsid = ssid;
                cachedBssid = info.getBSSID() != null ? info.getBSSID() : "UNKNOWN";
            }
        } catch (Exception ignored) {}

        // 如果当前在后台拿不到（系统返回 <unknown ssid>），则回退返回我们内存中记住的最后一次有效名称
        return new String[]{cachedSsid, cachedBssid};
    }

    // 获取内网 IPv4 地址
    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }
}
