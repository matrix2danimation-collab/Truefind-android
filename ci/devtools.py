#!/usr/bin/env python3
"""Runs one line of JavaScript in the app's sign-in web page on the test phone (Chrome DevTools, over adb).

    adb forward tcp:9222 localabstract:webview_devtools_remote_<pid of the app>
    python3 ci/devtools.py 9222 "location.pathname"

Test runs only (ci/session-test.sh): debug builds of the app let the test phone's tools reach the page.
Prints what the line gave back. Nothing but Python's own library is used.
"""
import base64, json, os, socket, struct, sys, urllib.request


def main():
    port = int(sys.argv[1]); expr = sys.argv[2]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    pages = json.load(opener.open(f"http://127.0.0.1:{port}/json", timeout=20))
    page = next((p for p in pages if p.get("type") == "page" and "true-find" in p.get("url", "")), None)
    if page is None:
        print("no True Find page among: " + ", ".join(p.get("url", "?") for p in pages)); sys.exit(2)
    path = "/" + page["webSocketDebuggerUrl"].split("/", 3)[3]
    s = socket.create_connection(("127.0.0.1", port), timeout=30)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
    head = b""
    while b"\r\n\r\n" not in head:
        chunk = s.recv(4096)
        if not chunk: break
        head += chunk
    if b" 101 " not in head.split(b"\r\n", 1)[0]:
        print("the page did not accept the connection: " + head.split(b"\r\n", 1)[0].decode(errors="replace")); sys.exit(3)
    rest = head.split(b"\r\n\r\n", 1)[1]
    payload = json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": expr, "returnByValue": True}}).encode()
    n = len(payload); mask = os.urandom(4)
    frame = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n) if n < 65536 else bytes([0x80 | 127]) + struct.pack(">Q", n))
    s.sendall(frame + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    buf = rest
    def need(k):
        nonlocal buf
        while len(buf) < k:
            chunk = s.recv(65536)
            if not chunk: raise EOFError("the page closed the connection")
            buf += chunk
        out, buf = buf[:k], buf[k:]
        return out
    try:
        for _ in range(50):   # the answer to our question (other messages may come first)
            b0, b1 = need(2)
            size = b1 & 0x7F
            if size == 126: size = struct.unpack(">H", need(2))[0]
            elif size == 127: size = struct.unpack(">Q", need(8))[0]
            data = need(size)
            if b0 & 0x0F != 1: continue
            msg = json.loads(data.decode(errors="replace"))
            if msg.get("id") == 1:
                r = msg.get("result", {}).get("result", {})
                print(r.get("value", r.get("description", json.dumps(msg)[:300])))
                return
    except (EOFError, socket.timeout) as e:
        # (a line that sends the page somewhere else can end the connection before the answer: that is fine)
        print(f"sent ({e})")
        return
    print("no answer")


if __name__ == "__main__":
    main()
