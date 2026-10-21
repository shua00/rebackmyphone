package com.example.devicefinder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;

public class TrackingService extends Service implements LocationListener {

    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback networkCallback;

    // 默认配置（可由服务端动态修改）
    private long reportIntervalMs = 1800 * 1000L; // 默认 30 分钟
    private String accuracyMode = "coarse";       
    private Location lastLocation = null;

    private static long lastReportTime = 0;
    
    // 失联自愈计数器与状态锁
    private static int consecutiveFailures = 0;
    private static boolean isListeningForRescue = false;

    // 默认初始上报地址（后续可由服务端下发新地址动态热更新覆盖）
    private static String defaultServerUrl = "http://192.168.1.123:8000/api/report";
    
    // 鉴权密匙与局域网救援安全密码（必须与 rescue.py 中的 RESCUE_SECRET 一致）
    private static final String AUTH_TOKEN = "your_private_secret_key";
    private static final String RESCUE_SECRET = "your_private_secret_key";

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startForegroundNotification();
        registerNetworkCallback();
        startTracking();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_MANUAL_REPORT".equals(intent.getAction())) {
            sendReport();
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
        startForeground(1001, notification);
    }

    // 方案一：动态生成并持久化唯一设备 ID (手机型号 + 4位随机数)
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

    // 动态获取当前生效的上报服务器地址
    private String getCurrentServerUrl() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        return sp.getString("saved_server_url", defaultServerUrl);
    }

    // 保存服务端下发的新服务器地址
    private void saveNewServerUrl(String newUrl) {
        if (newUrl != null && !newUrl.isEmpty()) {
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            sp.edit().putString("saved_server_url", newUrl).apply();
        }
    }

    // 注册网络变动回调（Wi-Fi 跨网即时插队上报）
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
                            sendReport();
                            scheduleNextReport(); 
                        }
                    }

                    @Override
                    public void onLost(Network network) {
                        super.onLost(network);
                        sendReport();
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
            sendReport();
            scheduleNextReport();
        }
    };

    private void scheduleNextReport() {
        handler.removeCallbacks(reportRunnable);
        handler.postDelayed(reportRunnable, reportIntervalMs);
    }

    private void sendReport() {
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

                    Location loc = lastLocation;
                    try {
                        if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    } catch (SecurityException ignored) {}

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
                    netObj.put("local_ip", ip);
                    json.put("network", netObj);

                    JSONObject locObj = new JSONObject();
                    locObj.put("latitude", loc != null ? loc.getLatitude() : 0.0);
                    locObj.put("longitude", loc != null ? loc.getLongitude() : 0.0);
                    locObj.put("accuracy", loc != null ? loc.getAccuracy() : -1.0);
                    locObj.put("provider", loc != null ? loc.getProvider() : "none");
                    json.put("location", locObj);

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

                // 智能失联自愈触发：连续失败达到 3 次时，自动开启 60 秒限时 UDP 监听等待救援
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

    // 60秒限时、带密码校验的局域网 UDP 自愈监听器（零额外日常耗电）
    private void startRescueListener() {
        isListeningForRescue = true;
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket rescueSocket = null;
                try {
                    rescueSocket = new DatagramSocket(8888);
                    rescueSocket.setBroadcast(true);
                    rescueSocket.setSoTimeout(60000); // 60秒自动超时关闭
                    
                    byte[] buffer = new byte[512];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    
                    while (true) {
                        rescueSocket.receive(packet); 
                        String message = new String(packet.getData(), 0, packet.getLength());
                        
                        // 暗号格式预期：FIND_ME_SERVER:密码:新服务器网址
                        if (message.startsWith("FIND_ME_SERVER:")) {
                            String[] parts = message.split(":", 3);
                            if (parts.length == 3) {
                                String receivedPassword = parts[1];
                                String newServerUrl = parts[2].trim();
                                
                                if (RESCUE_SECRET.equals(receivedPassword)) {
                                    saveNewServerUrl(newServerUrl);
                                    consecutiveFailures = 0;
                                    sendReport(); // 立即向新地址心跳证明复活
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

        // 动态热更新上报服务器地址
        String nextServerUrl = cfg.optString("next_server_url", "");
        if (!nextServerUrl.isEmpty() && !nextServerUrl.equals(getCurrentServerUrl())) {
            saveNewServerUrl(nextServerUrl);
        }
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
