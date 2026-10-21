import socket
import json

PORT = 8000
AUTH_TOKEN = "your_private_secret_key"

# 全局默认策略（可在此处随时动态下发频率、精度及热更新新服务器地址）
GLOBAL_DEFAULT_CONFIG = {
    "report_interval_seconds": 1800,
    "accuracy_mode": "coarse",
    "next_server_url": "http://192.168.1.123:8000/api/report"
}

# 内存数据库：存储所有备用机的最新情报
ALL_DEVICES_STATUS = {}

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

            # 1. 处理客户端 POST 定位/心跳上报
            if "POST /api/report" in request:
                try:
                    body = request.split("\r\n\r\n")[1]
                    data = json.loads(body)

                    # 校验鉴权 Token
                    if data.get("token") != AUTH_TOKEN:
                        client_conn.sendall(b"HTTP/1.1 403 Forbidden\r\n\r\n")
                        client_conn.close()
                        continue

                    dev_id = data.get("device_id", "unknown_device")
                    ALL_DEVICES_STATUS[dev_id] = data

                    # 终端打印格式化情报
                    print("\n" + "="*50)
                    print(f"📥 [收到上报] 设备: 【{dev_id}】")
                    print(f"🔋 电量: {data.get('battery', {}).get('level')}% (充电中: {data.get('battery', {}).get('is_charging')})")
                    print(f"🛜 Wi-Fi: {data.get('network', {}).get('wifi_ssid')} (内网IP: {data.get('network', {}).get('local_ip')})")
                    print(f"📍 定位: {data.get('location', {}).get('latitude')}, {data.get('location', {}).get('longitude')}")
                    print("="*50)

                    # 组装响应包，下发最新策略
                    response_body = json.dumps({
                        "status": "success",
                        "config": GLOBAL_DEFAULT_CONFIG
                    })

                    response = f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {len(response_body)}\r\n\r\n{response_body}"
                    client_conn.sendall(response.encode('utf-8'))
                except Exception as e:
                    print(f"❌ 上报解析异常: {e}")
                    client_conn.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")

            # 2. 处理浏览器 GET 访问状态看板
            elif "GET /" in request:
                html = "<html><head><meta charset='utf-8'><title>设备监控面板</title></head><body>"
                html += "<h2>🛡️ 备用机矩阵控制台</h2>"
                html += f"<h3>当前在线/已上报设备总数: {len(ALL_DEVICES_STATUS)}</h3>"
                html += "<table border='1' cellpadding='8' cellspacing='0'><tr><th>设备ID</th><th>电量</th><th>Wi-Fi名称</th><th>内网IP</th><th>最后经纬度</th></tr>"
                
                if len(ALL_DEVICES_STATUS) == 0:
                    html += "<tr><td colspan='5' align='center'>暂无设备上报数据</td></tr>"
                else:
                    for dev_id, info in ALL_DEVICES_STATUS.items():
                        batt = info.get('battery', {}).get('level', '?')
                        ssid = info.get('network', {}).get('wifi_ssid', '?')
                        ip = info.get('network', {}).get('local_ip', '?')
                        lat = info.get('location', {}).get('latitude', 0)
                        lng = info.get('location', {}).get('longitude', 0)
                        html += f"<tr><td><b>{dev_id}</b></td><td>{batt}%</td><td>{ssid}</td><td>{ip}</td><td>{lat}, {lng}</td></tr>"
                
                html += "</table></body></html>"
                
                response = f"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {len(html.encode('utf-8'))}\r\n\r\n{html}"
                client_conn.sendall(response.encode('utf-8'))
            else:
                client_conn.sendall(b"HTTP/1.1 404 Not Found\r\n\r\n")

            client_conn.close()
        except Exception as e:
            print(f"Socket 循环异常: {e}")

if __name__ == "__main__":
    start_socket_server()
