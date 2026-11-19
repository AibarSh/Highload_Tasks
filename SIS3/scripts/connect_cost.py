#!/usr/bin/env python3
"""Cost of 'connect -> one query -> disconnect' (what an app WITHOUT a pool pays per request),
direct to PostgreSQL vs through PgBouncer. Prints and writes percentiles in ms."""
import sys, time, json, psycopg2

def measure(port, dbname, n):
    lat = []
    for _ in range(n):
        t0 = time.perf_counter()
        c = psycopg2.connect(host="127.0.0.1", port=port, dbname=dbname)
        cur = c.cursor(); cur.execute("SELECT 1"); cur.fetchone()
        c.close()
        lat.append((time.perf_counter() - t0) * 1000)
    return lat

def pct(v, p):
    s = sorted(v); return s[max(0, int(-(-p / 100 * len(s) // 1)) - 1)]

if __name__ == "__main__":
    pg, pb, db, n, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], int(sys.argv[4]), sys.argv[5]
    res = {}
    for label, port in (("direct", pg), ("pgbouncer", pb)):
        measure(port, db, 50)                       # warm-up
        v = measure(port, db, n)
        res[label] = {"n": n, "mean": sum(v) / n, "p50": pct(v, 50), "p95": pct(v, 95), "p99": pct(v, 99)}
        print(f"{label:10s} mean={res[label]['mean']:.3f} p50={res[label]['p50']:.3f} p95={res[label]['p95']:.3f} p99={res[label]['p99']:.3f} ms")
    json.dump(res, open(out, "w"), indent=2)
