#!/usr/bin/env python3
"""
NLS 手环疗愈服务器 — 手机遥控版
通过 WiFi 局域网让手机浏览器控制 COM4 手环
"""

import json
import os
import time
import threading
import struct
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

try:
    import serial
except ImportError:
    serial = None
    print("[!] pyserial 未安装，COM4 不可用。运行: pip install pyserial")

# FTDI 双通道初始化（CBUS BitBang → 解决 LED 同步闪烁）
import ctypes
_FT_IOCTL_RESET = 0x00222004
_FT_IOCTL_BAUD  = 0x00222008
_FT_IOCTL_BIT   = 0x00222014

def _configure_ftdi(ser):
    try:
        k32 = ctypes.windll.kernel32
        handle = getattr(ser, '_port_handle', None) or getattr(ser, '_serial_handle', None)
        if not handle: return False
        ret = ctypes.c_ulong()
        k32.DeviceIoControl(handle, _FT_IOCTL_RESET, None, 0, None, 0, ctypes.byref(ret), None)
        baud = struct.pack('<I', 115200)
        k32.DeviceIoControl(handle, _FT_IOCTL_BAUD, baud, 4, None, 0, ctypes.byref(ret), None)
        bit = struct.pack('<IB', 0xFF, 0x40)
        k32.DeviceIoControl(handle, _FT_IOCTL_BIT, bit, len(bit), None, 0, ctypes.byref(ret), None)
        return True
    except: return False

PORT = 8080
DATA_DIR = os.path.join(os.path.dirname(__file__), "therapy_data")
COM_PORT = "COM4"
BAUD = 115200

# ============ 加载疗愈数据 ============

def load_programs():
    path = os.path.join(DATA_DIR, "therapy_programs.json")
    if not os.path.exists(path):
        return {}
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)

programs = load_programs()

# ============ 手环通信 ============

class HandRing:
    def __init__(self):
        self.ser = None
        self.playing = False
        self.current_program = None
        self.current_index = 0
        self.total_commands = 0
        self.interval = 0.5  # seconds between commands
        self._thread = None
        self._lock = threading.Lock()
        self.log = []

    def open(self):
        if serial is None:
            return False, "pyserial 未安装"
        try:
            self.ser = serial.Serial(COM_PORT, BAUD, timeout=2)
            if _configure_ftdi(self.ser):
                self.log_entry("[OK] FTDI 双通道已配置")
            self.log_entry(f"[OK] COM4 已连接 {BAUD}bps")
            return True, f"COM4 已连接 {BAUD}bps"
        except Exception as e:
            self.ser = None
            return False, str(e)

    def close(self):
        self.stop()
        if self.ser and self.ser.is_open:
            self.ser.close()
            self.ser = None

    def log_entry(self, msg):
        ts = time.strftime("%H:%M:%S")
        self.log.append(f"[{ts}] {msg}")
        if len(self.log) > 100:
            self.log = self.log[-100:]

    def send_command(self, cmd_hex):
        """发送一条 128 字节命令到手环"""
        if not self.ser or not self.ser.is_open:
            return False, "COM4 未连接"
        try:
            raw = bytes.fromhex(cmd_hex.replace(" ", ""))
            if len(raw) != 128:
                raw = raw.ljust(128, b'\x00')
            self.ser.write(raw)
            return True, "OK"
        except Exception as e:
            return False, str(e)

    def play_sequence(self, program_key, power_level=None, loop=True, repeat=1):
        """播发指定器官方案的疗愈序列"""
        if program_key not in programs:
            return False, f"未知方案: {program_key}"

        self.stop()
        prog = programs[program_key]

        if power_level is not None:
            cmds = [c for c in prog["commands"] if c["b11"] == power_level]
            if not cmds:
                cmds = [c for c in prog["commands"] if abs(c["b11"] - power_level) <= 5]
        else:
            cmds = prog["commands"]

        self._start_playing(program_key, cmds, loop, repeat)
        return True, f"正在播放: {program_key} ({len(cmds)} 条命令, ×{repeat})"

    def play_all(self, loop=False, repeat=1):
        """播放所有器官方案（1087条原始命令）"""
        self.stop()
        all_cmds = []
        for key in sorted(programs.keys(), key=lambda k: programs[k]["b9"]):
            all_cmds.extend(programs[key]["commands"])
        self._start_playing("全部器官", all_cmds, loop, repeat)
        return True, f"正在播放: 全部器官 ({len(all_cmds)} 条命令, ×{repeat})"

    def _start_playing(self, label, cmds, loop, repeat=1):
        self.current_program = label
        self.current_index = 0
        self.total_commands = len(cmds)
        self.playing = True

        def _play():
            idx = 0
            while self.playing and cmds:
                cmd = cmds[idx % len(cmds)]
                self.current_index = idx + 1
                # 每条命令重复发送 repeat 次
                for r in range(repeat):
                    if not self.playing:
                        break
                    ok, msg = self.send_command(cmd["cmd_hex"])
                    if r == 0 or repeat <= 1:
                        f_mhz = cmd.get('freq_mhz', 0)
                        b11 = cmd.get('b11', 0)
                        ch1_amp = int(b11 * 100 / 172)
                        self.log_entry(
                            f"[{'▶' if ok else '⚠'}] {label} "
                            f"#{idx+1}/{len(cmds)} CH1:{f_mhz:.0f}MHz {ch1_amp}% "
                            f"b9={cmd['b9']} b11={cmd['b11']}"
                            f"{cmd['freq_mhz']:.0f}MHz"
                        )
                    if r < repeat - 1:
                        time.sleep(self.interval)
                time.sleep(self.interval)
                idx += 1
                if not loop and idx >= len(cmds):
                    self.playing = False
                    self.log_entry("[■] 全部完成")
                    break
            if not loop:
                self.playing = False

        self._thread = threading.Thread(target=_play, daemon=True)
        self._thread.start()

    def stop(self):
        self.playing = False
        self.current_program = None
        if self._thread:
            self._thread = None
        self.log_entry("[■] 已停止")

    def status(self):
        return {
            "connected": self.ser is not None and self.ser.is_open,
            "playing": self.playing,
            "program": self.current_program,
            "current": self.current_index,
            "total": self.total_commands,
            "interval": self.interval,
        }

hand = HandRing()

# ============ HTTP 服务器 ============

HTML_PAGE = open(os.path.join(os.path.dirname(__file__), "therapy_mobile.html"),
                 "r", encoding="utf-8").read() if os.path.exists(
    os.path.join(os.path.dirname(__file__), "therapy_mobile.html")) else "<h1>UI not found</h1>"

class TherapyHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass  # 安静模式

    def send_json(self, data, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode())

    def send_html(self, html):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(html.encode())

    def do_GET(self):
        p = urlparse(self.path)

        if p.path == "/" or p.path == "/index.html":
            self.send_html(HTML_PAGE)
            return

        if p.path == "/api/programs":
            # 返回所有疗愈方案
            data = {}
            for key, prog in programs.items():
                data[key] = {
                    "name": key,
                    "organ": prog["organ"],
                    "freq_range": prog["freq_range"],
                    "power_levels": sorted(set(prog["power_levels"])),
                    "command_count": prog["command_count"],
                    "commands": prog["commands"],  # 全部命令
                }
            self.send_json(data)
            return

        if p.path == "/api/status":
            self.send_json(hand.status())
            return

        if p.path == "/api/log":
            self.send_json({"log": hand.log})
            return

        if p.path == "/api/connect":
            ok, msg = hand.open()
            self.send_json({"ok": ok, "message": msg})
            return

        if p.path == "/api/disconnect":
            hand.close()
            self.send_json({"ok": True, "message": "已断开"})
            return

        self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)
        try:
            params = json.loads(body)
        except:
            params = {}

        p = urlparse(self.path)

        if p.path == "/api/play":
            prog = params.get("program", "")
            power = params.get("power", None)
            loop = params.get("loop", True)
            interval = params.get("interval", None)
            repeat = int(params.get("repeat", 1))
            if interval:
                hand.interval = float(interval)
            ok, msg = hand.play_sequence(prog, power, loop, repeat)
            self.send_json({"ok": ok, "message": msg})
            return

        if p.path == "/api/play-all":
            loop = params.get("loop", False)
            interval = params.get("interval", None)
            repeat = int(params.get("repeat", 1))
            if interval:
                hand.interval = float(interval)
            ok, msg = hand.play_all(loop, repeat)
            self.send_json({"ok": ok, "message": msg})
            return

        if p.path == "/api/stop":
            hand.stop()
            self.send_json({"ok": True, "message": "已停止"})
            return

        if p.path == "/api/interval":
            val = params.get("value", 0.5)
            if val is not None:
                hand.interval = float(val)
            self.send_json({"ok": True, "interval": hand.interval})
            return

        if p.path == "/api/raw":
            cmd_hex = params.get("cmd", "")
            ok, msg = hand.send_command(cmd_hex)
            self.send_json({"ok": ok, "message": msg})
            return

        self.send_json({"error": "not found"}, 404)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()


def main():
    print("=" * 55)
    print("  NLS 手环疗愈服务器 v1.0")
    print(f"  手机访问: http://{get_local_ip()}:{PORT}")
    print(f"  加载方案: {len(programs)} 个器官系统")
    print(f"  总命令数: {sum(p['command_count'] for p in programs.values())}")
    print("=" * 55)

    server = HTTPServer(("0.0.0.0", PORT), TherapyHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] 服务器已停止")
        hand.close()
        server.server_close()


def get_local_ip():
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except:
        return "127.0.0.1"


if __name__ == "__main__":
    main()
