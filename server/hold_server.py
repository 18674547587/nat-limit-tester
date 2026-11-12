#!/usr/bin/env python3
"""
nat-limit-tester —— TCP 保持计数服务端

职责很窄：接受 TCP 连接后「不读不写只 hold」，并精确统计：
  - current  当前存活连接数
  - peak     本轮峰值（可 /reset 清零）
  - total    累计 accept 次数
  - refused  超过 MAX_CONNS 被拒绝的次数
  - per_ip   按对端 IP 聚合（NAT 后同一公网 IP，用来看是不是只有一个来源）

为什么要服务端独立计数：
  TCP 三次握手可能被本地 NAT / 中间设备「代答」成功，客户端以为连上了，
  但服务端根本没收到。只有服务端自己数一遍，才能和客户端对账。

控制面走独立端口（默认 9002），带 token 鉴权，避免被公网随便拉取。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import socket
import time
from collections import defaultdict


class Stats:
    def __init__(self, max_conns: int):
        self.max_conns = max_conns
        self.current = 0
        self.peak = 0
        self.total = 0
        self.refused = 0
        self.per_ip_cur = defaultdict(int)
        self.per_ip_peak = defaultdict(int)
        self.started = time.time()
        self.reset_at = time.time()

    def snapshot(self) -> dict:
        return {
            "ok": True,
            "current": self.current,
            "peak": self.peak,
            "total": self.total,
            "refused": self.refused,
            "max_conns": self.max_conns,
            "at_capacity": self.current >= self.max_conns,
            "uptime_s": round(time.time() - self.started, 1),
            "since_reset_s": round(time.time() - self.reset_at, 1),
            "clients": len(self.per_ip_cur),
            "per_ip": {
                ip: {"current": self.per_ip_cur[ip], "peak": self.per_ip_peak[ip]}
                for ip in sorted(self.per_ip_cur, key=lambda k: -self.per_ip_cur[k])[:20]
            },
        }

    def reset(self) -> None:
        self.peak = 0
        self.total = 0
        self.refused = 0
        self.per_ip_peak.clear()
        self.reset_at = time.time()


STATS: Stats | None = None
VERBOSE = False


async def handle_conn(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    assert STATS is not None
    sock = writer.get_extra_info("socket")
    peer = writer.get_extra_info("peername") or ("?", 0)
    ip = peer[0]

    # 压小内核 socket 缓冲，单连接内存开销从 ~130KB 降到几 KB
    if sock is not None:
        for opt, val in ((socket.SO_RCVBUF, 4096), (socket.SO_SNDBUF, 4096)):
            try:
                sock.setsockopt(socket.SOL_SOCKET, opt, val)
            except OSError:
                pass

    if STATS.current >= STATS.max_conns:
        STATS.refused += 1
        try:
            writer.close()
        except Exception:
            pass
        return

    STATS.current += 1
    STATS.total += 1
    STATS.per_ip_cur[ip] += 1
    if STATS.current > STATS.peak:
        STATS.peak = STATS.current
    if STATS.per_ip_cur[ip] > STATS.per_ip_peak[ip]:
        STATS.per_ip_peak[ip] = STATS.per_ip_cur[ip]

    if VERBOSE and STATS.total % 500 == 0:
        print(f"[hold] total={STATS.total} current={STATS.current} peak={STATS.peak}", flush=True)

    try:
        # 不读不写：只是等对端关闭。read(1) 在无数据时会挂起到 EOF。
        await reader.read(1)
        while True:
            chunk = await reader.read(4096)
            if not chunk:
                break
    except (ConnectionResetError, asyncio.IncompleteReadError, OSError):
        pass
    finally:
        STATS.current -= 1
        STATS.per_ip_cur[ip] -= 1
        if STATS.per_ip_cur[ip] <= 0:
            del STATS.per_ip_cur[ip]
        try:
            writer.close()
        except Exception:
            pass


async def handle_http(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    assert STATS is not None
    try:
        head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5)
    except (asyncio.TimeoutError, asyncio.IncompleteReadError, OSError):
        writer.close()
        return

    line = head.split(b"\r\n", 1)[0].decode("latin-1")
    parts = line.split(" ")
    method = parts[0] if parts else ""
    target = parts[1] if len(parts) > 1 else "/"
    path, _, query = target.partition("?")

    token = os.environ.get("NLT_TOKEN", "")
    given = ""
    for kv in query.split("&"):
        if kv.startswith("token="):
            given = kv[6:]

    if token and given != token:
        body = b'{"ok":false,"error":"bad token"}'
        status = "403 Forbidden"
    elif path == "/stats":
        body = json.dumps(STATS.snapshot()).encode()
        status = "200 OK"
    elif path == "/reset":
        STATS.reset()
        body = b'{"ok":true,"reset":true}'
        status = "200 OK"
    elif path == "/ping":
        body = b'{"ok":true}'
        status = "200 OK"
    else:
        body = b'{"ok":false,"error":"not found"}'
        status = "404 Not Found"

    writer.write(
        f"HTTP/1.1 {status}\r\nContent-Type: application/json\r\n"
        f"Content-Length: {len(body)}\r\nConnection: close\r\n"
        f"Access-Control-Allow-Origin: *\r\n\r\n".encode()
        + body
    )
    try:
        await writer.drain()
    except OSError:
        pass
    writer.close()


async def main() -> None:
    global STATS, VERBOSE
    ap = argparse.ArgumentParser()
    ap.add_argument("--hold-port", type=int, default=9001)
    ap.add_argument("--http-port", type=int, default=9002)
    ap.add_argument("--max-conns", type=int, default=15000)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    VERBOSE = args.verbose
    STATS = Stats(args.max_conns)

    hold = await asyncio.start_server(handle_conn, args.bind, args.hold_port, backlog=8192)
    http = await asyncio.start_server(handle_http, args.bind, args.http_port, backlog=128)

    print(f"[hold]  TCP hold  : {args.bind}:{args.hold_port}  max={args.max_conns}", flush=True)
    print(f"[stats] HTTP 控制 : {args.bind}:{args.http_port}  /stats /reset /ping", flush=True)

    async with hold, http:
        await asyncio.gather(hold.serve_forever(), http.serve_forever())


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
