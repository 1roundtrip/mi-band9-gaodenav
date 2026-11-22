#!/usr/bin/env python3
"""手机调试助手：同一 WiFi 下，手机浏览器直接装 APK、上传截图到电脑。"""
import datetime
import html
import os
import re
import socket
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "android-app" / "dist" / "cn.dsh.gaodenav.apk"
SHOTS = ROOT / "screenshots"
PORT = int(os.environ.get("DEV_LOOP_PORT", "8765"))

PAGE = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>高德导航 · 手机调试助手</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f5f6f8;color:#1a1a1a;font:16px/1.5 -apple-system,"PingFang SC",sans-serif}
.wrap{max-width:560px;margin:0 auto;padding:20px 16px 40px}
h1{font-size:20px;margin:4px 0 2px}.sub{color:#888;font-size:13px;margin-bottom:18px}
.card{background:#fff;border-radius:14px;padding:16px;margin-bottom:14px;box-shadow:0 2px 12px #0000000d}
.card h2{font-size:15px;margin:0 0 10px}
.btn{display:block;width:100%;padding:15px;border:0;border-radius:12px;background:#1a73e8;color:#fff;font-size:17px;font-weight:600;text-align:center;text-decoration:none;margin-top:8px}
.btn.green{background:#12a150}.btn:active{opacity:.8}
.meta{font-size:13px;color:#777;margin-top:8px}
input[type=file]{width:100%;padding:10px;border:1px dashed #bbb;border-radius:10px;background:#fafafa}
ol{font-size:13px;color:#666;padding-left:20px;margin:10px 0 0}
.shot{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #eee;font-size:14px}
.shot img{width:52px;height:52px;object-fit:cover;border-radius:8px;background:#eee}
.shot a{color:#1a73e8;text-decoration:none;flex:1;word-break:break-all}
code{background:#eef1f5;padding:2px 5px;border-radius:4px}
</style></head><body><div class="wrap">
<h1>高德导航 · 手机调试助手</h1>
<div class="sub">电脑上跑着这个服务，手机不用再微信传文件</div>
<div class="card"><h2>① 安装最新版 APK</h2>
@@APK_LINE@@
<a class="btn green" href="/apk">下载并安装 APK</a>
<div class="meta">下载后用文件管理器点开安装；若提示“未知来源”，允许一次即可。</div></div>
<div class="card"><h2>② 把截图传给电脑</h2>
<form method="post" action="/upload" enctype="multipart/form-data">
<input type="file" name="shot" accept="image/*" required>
<button class="btn" type="submit">上传截图</button></form>
<div class="meta">上传后我这边立刻能看到，不用再截图发微信。</div></div>
<div class="card"><h2>最近收到的截图</h2>@@SHOTS@@</div>
<div class="card"><h2>小提示</h2>
<ol><li>手机和电脑要在同一个 WiFi。</li><li>这个页面地址是 <code>@@URL@@</code>，可以存成书签。</li>
<li>装完打开一次 App，有问题直接在这里传截图。</li></ol></div>
</div></body></html>"""


def lan_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("223.5.5.5", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                ips.add(ip)
    except Exception:
        pass
    return sorted(ips)


def safe_name(name):
    name = os.path.basename(name.replace("\\", "/"))
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    return name[-80:] or "shot.png"


def parse_upload(body, boundary):
    for part in body.split(b"--" + boundary):
        if part.startswith(b"\r\n"):
            part = part[2:]
        if part.endswith(b"\r\n"):
            part = part[:-2]
        head, sep, data = part.partition(b"\r\n\r\n")
        if not sep:
            continue
        cd = ""
        for line in head.split(b"\r\n"):
            key, _, val = line.partition(b":")
            if key.strip().lower() == b"content-disposition":
                cd = val.decode("utf-8", "ignore")
        m = re.search(r'filename="([^"]*)"', cd)
        if m and m.group(1):
            yield safe_name(m.group(1)), data


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.address_string(), fmt % args), flush=True)

    def send(self, code, body, ctype="text/html; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path in ("/", "", "/index.html"):
            if APK.exists():
                st = APK.stat()
                apk_line = "版本 V2.1.0 · %.1f MB · 更新于 %s" % (
                    st.st_size / 1048576,
                    datetime.datetime.fromtimestamp(st.st_mtime).strftime("%m-%d %H:%M"),
                )
            else:
                apk_line = "<b style='color:#c00'>没找到 APK，请先在电脑上构建</b>"
            shots = ""
            for f in sorted(SHOTS.glob("*"), key=lambda p: p.stat().st_mtime, reverse=True)[:8]:
                thumb = '<img src="/file/%s" alt="">' % urllib.parse.quote(f.name) if f.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp") else ""
                shots += '<div class="shot">%s<a href="/file/%s">%s</a><span>%s</span></div>' % (
                    thumb, urllib.parse.quote(f.name), html.escape(f.name),
                    datetime.datetime.fromtimestamp(f.stat().st_mtime).strftime("%m-%d %H:%M"),
                )
            if not shots:
                shots = '<div class="meta">还没有收到截图</div>'
            url = "http://%s:%d" % (lan_ips()[0] if lan_ips() else "电脑IP", PORT)
            page = (PAGE.replace("@@APK_LINE@@", apk_line)
                        .replace("@@SHOTS@@", shots)
                        .replace("@@URL@@", html.escape(url)))
            self.send(200, page.encode("utf-8"))
        elif path == "/apk":
            if not APK.exists():
                self.send(404, b"APK not found", "text/plain")
                return
            data = APK.read_bytes()
            self.send(200, data, "application/octet-stream", {
                "Content-Disposition": 'attachment; filename="cn.dsh.gaodenav.apk"',
            })
        elif path.startswith("/file/"):
            name = urllib.parse.unquote(path[len("/file/"):])
            f = SHOTS / safe_name(name)
            if not f.is_file():
                self.send(404, b"not found", "text/plain")
                return
            self.send(200, f.read_bytes(), "image/png" if f.suffix.lower() == ".png" else "image/jpeg")
        else:
            self.send(404, b"not found", "text/plain")

    def do_POST(self):
        if urllib.parse.urlsplit(self.path).path != "/upload":
            self.send(404, b"not found", "text/plain")
            return
        length = int(self.headers.get("Content-Length", 0))
        ctype = self.headers.get("Content-Type", "")
        m = re.search(r"boundary=(?:\"([^\"]+)\"|([^;]+))", ctype)
        if not m or length <= 0:
            self.send(400, b"bad upload", "text/plain")
            return
        boundary = (m.group(1) or m.group(2)).strip().encode("ascii", "ignore")
        body = self.rfile.read(length)
        SHOTS.mkdir(exist_ok=True)
        saved = []
        for name, data in parse_upload(body, boundary):
            stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
            out = SHOTS / ("%s-%s" % (stamp, name))
            out.write_bytes(data)
            saved.append(out.name)
            print("[截图] 已保存 %s (%d bytes)" % (out, len(data)), flush=True)
        self.send(200, ("上传成功：%s。回到手机调试助手页面就能看到。" % ", ".join(saved)).encode("utf-8"))


def main():
    SHOTS.mkdir(exist_ok=True)
    if not APK.exists():
        print("警告：没找到 %s" % APK, flush=True)
    ips = lan_ips()
    print("=" * 52, flush=True)
    print("手机调试助手已启动", flush=True)
    for ip in ips:
        print("  手机浏览器打开: http://%s:%d" % (ip, PORT), flush=True)
    print("按 Ctrl+C 停止", flush=True)
    print("=" * 52, flush=True)
    with ThreadingHTTPServer(("0.0.0.0", PORT), Handler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n已停止", flush=True)


if __name__ == "__main__":
    main()
