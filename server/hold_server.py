#!/usr/bin/env python3
"""
nat-limit-tester —— TCP 保持计数服务端（单端口复用版）

职责很窄：接受 TCP 连接后「不读不写只 hold」，并精确统计：
  current / peak / total / refused / per_ip

为什么要服务端独立计数：
  TCP 三次握手可能被本地 NAT 或中间设备「代答」成功，客户端以为连上了，
  服务端根本没收到。只有服务端自己数一遍，才能和客户端对账。

单端口复用（关键设计）：
  同一端口既接 hold 长连接，又提供 HTTP /stats 控制面。
  识别方式：accept 后等 HTTP_PROBE 秒，期间收到数据 → 当 HTTP 处理；
  收不到 → 当 hold 连接。hold 客户端本来就不发数据，所以判定可靠。
  这样只需要在云安全组放行一个端口（80）。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import socket
import time
from collections import defaultdict

HTTP_PROBE = 0.20


class Stats:
    def __init__(self, max_conns: int):
        self.max_conns = max_conns
        self.current = 0
        self.peak = 0
        self.total = 0
        self.refused = 0
        self.http_hits = 0
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
            "http_hits": self.http_hits,
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


async def handle_hold(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
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


async def handle_http(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, prefix: bytes) -> None:
    assert STATS is not None
    STATS.http_hits += 1
    try:
        rest = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5)
        head = prefix + rest
    except (asyncio.TimeoutError, asyncio.IncompleteReadError, OSError):
        writer.close()
        return
    await respond_http(writer, head)


async def respond_http(writer: asyncio.StreamWriter, head: bytes) -> None:
    assert STATS is not None
    line = head.split(b"\r\n", 1)[0].decode("latin-1")
    parts = line.split(" ")
    target = parts[1] if len(parts) > 1 else "/"
    path, _, query = target.partition("?")

    token = os.environ.get("NLT_TOKEN", "")
    given = ""
    for kv in query.split("&"):
        if kv.startswith("token="):
            given = kv[6:]

    if token and given != token:
        body, status = b'{"ok":false,"error":"bad token"}', "403 Forbidden"
    elif path == "/stats":
        body, status = json.dumps(STATS.snapshot()).encode(), "200 OK"
    elif path == "/reset":
        STATS.reset()
        body, status = b'{"ok":true,"reset":true}', "200 OK"
    elif path == "/ping":
        body, status = b'{"ok":true}', "200 OK"
    else:
        body, status = b'{"ok":false,"error":"not found"}', "404 Not Found"

    writer.write(
        f"HTTP/1.1 {status}\r\nContent-Type: application/json\r\n"
        f"Content-Length: {len(body)}\r\nConnection: close\r\n"
        f"Access-Control-Allow-Origin: *\r\n\r\n".encode() + body
    )
    try:
        await writer.drain()
    except OSError:
        pass
    writer.close()


async def handle_conn(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    """先判断是 HTTP 还是 hold，再分派。"""
    first = b""
    try:
        first = await asyncio.wait_for(reader.read(1), timeout=HTTP_PROBE)
    except asyncio.TimeoutError:
        first = b""
    except (ConnectionResetError, OSError):
        try:
            writer.close()
        except Exception:
            pass
        return

    if first:
        await handle_http(reader, writer, first)
    else:
        await handle_hold(reader, writer)


async def main() -> None:
    global STATS, VERBOSE
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=80, help="hold + HTTP 复用端口")
    ap.add_argument("--extra-http-port", type=int, default=0, help="额外开一个纯 HTTP 端口（内网自测用）")
    ap.add_argument("--max-conns", type=int, default=12000)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    VERBOSE = args.verbose
    STATS = Stats(args.max_conns)

    main_srv = await asyncio.start_server(handle_conn, args.bind, args.port, backlog=8192)
    print(f"[hold]  {args.bind}:{args.port}  max={args.max_conns}  (HTTP 复用同端口)", flush=True)

    servers = [main_srv]
    if args.extra_http_port:
        async def http_only(r, w):
            assert STATS is not None
            STATS.http_hits += 1
            try:
                head = await asyncio.wait_for(r.readuntil(b"\r\n\r\n"), timeout=5)
            except Exception:
                w.close()
                return
            await respond_http(w, head)
        s2 = await asyncio.start_server(http_only, args.bind, args.extra_http_port, backlog=128)
        servers.append(s2)
        print(f"[stats] {args.bind}:{args.extra_http_port}  /stats /reset /ping", flush=True)

    await asyncio.gather(*(s.serve_forever() for s in servers))


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
