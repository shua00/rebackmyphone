import socket
import time

# 必须与客户端代码中的 RESCUE_SECRET 保持一致
RESCUE_SECRET = "your_private_secret_key"
NEW_SERVER_URL = "http://192.168.1.123:8000/api/report"

def send_secure_rescue():
    # 组装带安全密码的救援暗号
    message = f"FIND_ME_SERVER:{RESCUE_SECRET}:{NEW_SERVER_URL}"
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    
    print("🔒 正在向局域网广播【带密码校验】的安全救援信号（持续 70 秒）...")
    
    for i in range(7):
        print(f"[{i+1}/7] 正在呼叫失联备用机...")
        sock.sendto(message.encode('utf-8'), ('255.255.255.255', 8888))
        time.sleep(10)
        
    sock.close()
    print("✅ 安全救援广播发送完毕。")

if __name__ == "__main__":
    send_secure_rescue()
