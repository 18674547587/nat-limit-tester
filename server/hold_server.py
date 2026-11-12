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
import struct
import threading
import time
from collections import defaultdict

HTTP_PROBE = 0.20


class UdpTracker:
    """
    UDP 会话统计：靠 AF_PACKET 抓包，不依赖内核给不给端口。

    为什么这么干：
      UDP 无连接，NAT 表项是按 (源IP,源端口,目的IP,目的端口) 建的。
      App 用同一个 socket 打不同「目的端口」，每个目的端口就是一条独立会话。
      服务端不需要真的监听那些端口 —— 只要网卡上能看到包，就能数出来。

    只统计入站、目的端口在 [port_min, port_max] 的包，避免把 DNS/QUIC 算进来。
    """

    def __init__(self, port_min: int, port_max: int, idle_timeout: float = 90.0):
        self.port_min = port_min
        self.port_max = port_max
        self.idle_timeout = idle_timeout
        self.flows: dict[tuple, float] = {}
        self.peak = 0
        self.total_pkts = 0
        self.reset_at = time.time()
        self.lock = threading.Lock()

    def touch(self, src_ip: str, src_port: int, dst_port: int, now: float) -> None:
        key = (src_ip, src_port, dst_port)
        with self.lock:
            self.flows[key] = now
            self.total_pkts += 1
            n = len(self.flows)
            if n > self.peak:
                self.peak = n

    def gc(self, now: float) -> None:
        with self.lock:
            dead = [k for k, t in self.flows.items() if now - t > self.idle_timeout]
            for k in dead:
                del self.flows[k]

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "sessions": len(self.flows),
                "peak": self.peak,
                "total_pkts": self.total_pkts,
                "port_range": [self.port_min, self.port_max],
                "clients": len({k[0] for k in self.flows}),
                "since_reset_s": round(time.time() - self.reset_at, 1),
            }

    def reset(self) -> None:
        with self.lock:
            self.peak = 0
            self.total_pkts = 0
            self.flows.clear()
            self.reset_at = time.time()


def udp_srcport_loop(bind_port: int, tracker: UdpTracker,
                     stop_event: threading.Event) -> None:
    """
    UDP 会话统计：在 UDP 20120 上真实监听，按「源端口」区分会话。

    为什么是这个方案（被现实逼出来的）：
      1) 用不同「目的端口」造会话最省 fd，但阿里云安全组只放行了 20120 一个端口，
         发到 10000-12999 的包全被丢弃，测不出来。
      2) 用 AF_PACKET 抓包 + 不监听端口，内核会回 ICMP port unreachable，
         路由器收到 ICMP 会立刻删掉对应 NAT 表项，会话数永远归零。
      3) 所以：目的端口固定 20120（已放行），每个会话换一个「源端口」。
         服务端在这个端口上真的收包，内核不会回 ICMP。

    代价：App 端每条会话要占一个 socket = 一个 fd，和 TCP 模式一样受 fd 限制。
    好处：不需要用户改任何安全组规则。
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    except OSError:
        pass
    try:
        s.bind(("0.0.0.0", bind_port))
    except OSError as e:
        print(f"[udp]  绑定 UDP {bind_port} 失败：{e}，UDP 统计未启用", flush=True)
        return

    s.settimeout(1.0)
    print(f"[udp]  监听 UDP {bind_port}，按源端口统计会话数", flush=True)

    last_gc = time.time()
    while not stop_event.is_set():
        try:
            _data, addr = s.recvfrom(2048)
        except socket.timeout:
            now = time.time()
            if now - last_gc > 5:
                tracker.gc(now)
                last_gc = now
            continue
        except OSError:
            continue

        now = time.time()
        tracker.touch(addr[0], addr[1], bind_port, now)
        if now - last_gc > 5:
            tracker.gc(now)
            last_gc = now

    try:
        s.close()
    except OSError:
        pass


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
            "udp": UDP.snapshot() if UDP is not None else None,
        }

    def reset(self) -> None:
        self.peak = 0
        self.total = 0
        self.refused = 0
        self.per_ip_peak.clear()
        self.reset_at = time.time()
        if UDP is not None:
            UDP.reset()


STATS: Stats | None = None
UDP: UdpTracker | None = None
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

    # /stats 与 /ping 只读且不含敏感信息，免 token（App 免配置即可对账）
    if path == "/stats":
        body, status = json.dumps(STATS.snapshot()).encode(), "200 OK"
    elif path == "/ping":
        body, status = b'{"ok":true}', "200 OK"
    elif token and given != token:
        body, status = b'{"ok":false,"error":"bad token"}', "403 Forbidden"
    elif path == "/reset":
        STATS.reset()
        body, status = b'{"ok":true,"reset":true}', "200 OK"
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
    ap.add_argument("--udp-port", type=int, default=20120,
                    help="UDP 会话统计端口（按源端口区分会话），设 0 关闭")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    global UDP
    VERBOSE = args.verbose
    STATS = Stats(args.max_conns)

    stop_event = threading.Event()
    if args.udp_port:
        UDP = UdpTracker(args.udp_port, args.udp_port)
        threading.Thread(
            target=udp_srcport_loop,
            args=(args.udp_port, UDP, stop_event),
            name="udp-srcport", daemon=True,
        ).start()

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
