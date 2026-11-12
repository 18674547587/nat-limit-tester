#!/usr/bin/env python3
"""本地自测：建 N 条 TCP 连接保持 HOLD 秒，打印建连成败与耗时。"""
import asyncio, socket, sys, time

async def one(host, port, timeout=5.0):
    t0 = time.perf_counter()
    try:
        r, w = await asyncio.wait_for(asyncio.open_connection(host, port), timeout)
        s = w.get_extra_info("socket")
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        except OSError:
            pass
        return ("ok", w, (time.perf_counter() - t0) * 1000)
    except asyncio.TimeoutError:
        return ("timeout", None, (time.perf_counter() - t0) * 1000)
    except OSError as e:
        return (f"err:{e.errno}:{e.strerror}", None, (time.perf_counter() - t0) * 1000)

async def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    hold = float(sys.argv[2]) if len(sys.argv) > 2 else 10
    host, port = "127.0.0.1", 9001
    sem = asyncio.Semaphore(200)
    res = []

    async def task():
        async with sem:
            res.append(await one(host, port))

    t0 = time.perf_counter()
    await asyncio.gather(*(task() for _ in range(n)))
    ok = [r for r in res if r[0] == "ok"]
    lat = sorted(r[2] for r in ok)
    print(f"目标 {n}  成功 {len(ok)}  失败 {n-len(ok)}  建连耗时 {time.perf_counter()-t0:.2f}s")
    if lat:
        print(f"单连延迟 ms: min={lat[0]:.2f} p50={lat[len(lat)//2]:.2f} max={lat[-1]:.2f}")
    for r in res:
        if r[0] != "ok":
            print("  失败样本:", r[0])
            break
    print(f"保持 {hold}s ...")
    await asyncio.sleep(hold)
    for r in ok:
        r[1].close()
    print("已全部关闭")

asyncio.run(main())
