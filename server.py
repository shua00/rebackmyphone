import socket
import json
import datetime

PORT = 8000
AUTH_TOKEN = "your_private_secret_key"

GLOBAL_DEFAULT_CONFIG = {
    "report_interval_seconds": 1800,
    "accuracy_mode": "coarse",
    "next_server_url": "http://192.168.1.123:8000/api/report"
}

ALL_DEVICES_STATUS = {}

def format_loc_time(ts_ms):
    if not ts_ms:
        return "N/A"
    try:
        dt = datetime.datetime.fromtimestamp(ts_ms / 1000.0)
        delta = datetime.datetime.now() - dt
        seconds = int(delta.total_seconds())
        if seconds < 60:
            ago = f"{seconds}秒前"
        elif seconds < 3600:
            ago = f"{seconds // 60}分钟前"
        elif seconds < 86400:
            ago = f"{seconds // 3600}小时前"
        else:
            ago = f"{seconds // 86400}天前"
        return f"{dt.strftime('%m-%d %H:%M:%S')} ({ago})"
    except Exception:
        return "N/A"

def start_socket_server():
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind(('0.0.0.0', PORT))
    server_socket.listen(10)

    print(f"🚀 稳定版多设备定位服务器已成功启动，监听端口 {PORT}...")
    print(f"👉 浏览器查看面板: http://你的电脑IP:{PORT}")

    while True:
        try:
            client_conn, client_addr = server_socket.accept()
            request = client_conn.recv(16384).decode('utf-8', errors='ignore')
            if not request:
                client_conn.close()
                continue

            if "POST /api/report" in request:
                try:
                    body = request.split("\r\n\r\n")[1]
                    data = json.loads(body)

                    if data.get("token") != AUTH_TOKEN:
                        client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\n\r\n")
                        client_conn.close()
                        continue

                    dev_id = data.get("device_id", "unknown_device")
                    ALL_DEVICES_STATUS[dev_id] = data

                    print("\n" + "="*50)
                    print(f"📥 [收到上报] 设备: 【{dev_id}】")
                    print(f"🔋 电量: {data.get('battery', {}).get('level')}% (充电中: {data.get('battery', {}).get('is_charging')})")
                    print(f"🛜 Wi-Fi: {data.get('network', {}).get('wifi_ssid')} "
                          f"(RSSI: {data.get('network', {}).get('wifi_rssi')} dBm, "
                          f"内网IP: {data.get('network', {}).get('local_ip')})")
                    loc = data.get('location', {})
                    print(f"📍 定位: {loc.get('latitude')}, {loc.get('longitude')} "
                          f"[{loc.get('provider')}, 精度 {loc.get('accuracy')}m]")
                    print(f"🕒 定位时间: {format_loc_time(loc.get('time', 0))}")
                    nearby = data.get('nearby_wifi', [])
                    print(f"📡 周围 Wi-Fi 热点数: {len(nearby)}")
                    if nearby:
                        for ap in nearby[:5]:
                            print(f"    - {ap.get('ssid')} ({ap.get('bssid')}) {ap.get('rssi')} dBm")
                        if len(nearby) > 5:
                            print(f"    ... 其余 {len(nearby) - 5} 个省略")
                    print("="*50)

                    response_body = json.dumps({
                        "status": "success",
                        "config": GLOBAL_DEFAULT_CONFIG
                    })

                    response = f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {len(response_body)}\r\n\r\n{response_body}"
                    client_conn.sendall(response.encode('utf-8'))
                except Exception as e:
                    print(f"❌ 上报解析异常: {e}")
                    client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")

            elif "GET /" in request:
                html = "<html><head><meta charset='utf-8'><title>设备监控面板</title>"
                html += "<style>"
                html += "body{font-family:sans-serif;margin:20px;}"
                html += "table{border-collapse:collapse;}"
                html += "th,td{border:1px solid #ccc;padding:8px;text-align:left;}"
                html += "th{background:#f0f0f0;}"
                html += "tr:nth-child(even){background:#fafafa;}"
                html += "</style>"
                html += "</head><body>"
                html += "<h2>🛡️ 备用机矩阵控制台</h2>"
                html += f"<h3>当前在线/已上报设备总数: {len(ALL_DEVICES_STATUS)}</h3>"
                html += "<table>"
                html += "<tr><th>设备ID</th><th>电量</th><th>Wi-Fi名称</th><th>RSSI</th>"
                html += "<th>内网IP</th><th>最后经纬度</th><th>定位精度</th><th>定位时间</th><th>周围热点</th></tr>"

                if len(ALL_DEVICES_STATUS) == 0:
                    html += "<tr><td colspan='9' align='center'>暂无设备上报数据</td></tr>"
                else:
                    for dev_id, info in ALL_DEVICES_STATUS.items():
                        batt = info.get('battery', {}).get('level', '?')
                        charging = "⚡" if info.get('battery', {}).get('is_charging') else ""
                        ssid = info.get('network', {}).get('wifi_ssid', '?')
                        rssi = info.get('network', {}).get('wifi_rssi', '?')
                        ip = info.get('network', {}).get('local_ip', '?')
                        lat = info.get('location', {}).get('latitude', 0)
                        lng = info.get('location', {}).get('longitude', 0)
                        acc = info.get('location', {}).get('accuracy', -1)
                        loc_time = info.get('location', {}).get('time', 0)
                        loc_time_str = format_loc_time(loc_time)
                        nearby_count = len(info.get('nearby_wifi', []))

                        html += f"<tr
