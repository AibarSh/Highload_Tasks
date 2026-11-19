#!/usr/bin/env python3
"""Read-your-writes gap: commit a row on the PRIMARY, then poll the REPLICA until the row is visible.
Reports how long a client could read stale data (ms). Run it while the benchmark load is active."""
import sys, time, json, psycopg2

def pct(v, p):
    s = sorted(v); return s[max(0, int(-(-p / 100 * len(s) // 1)) - 1)]

if __name__ == "__main__":
    pport, rport, db, n, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], int(sys.argv[4]), sys.argv[5]
    p = psycopg2.connect(host="127.0.0.1", port=pport, dbname=db); p.autocommit = True
    r = psycopg2.connect(host="127.0.0.1", port=rport, dbname=db); r.autocommit = True
    pc, rc = p.cursor(), r.cursor()
    lat = []
    for _ in range(n):
        pc.execute("INSERT INTO repl_probe DEFAULT VALUES RETURNING id")
        pid = pc.fetchone()[0]
        t0 = time.perf_counter()
        while True:
            rc.execute("SELECT 1 FROM repl_probe WHERE id = %s", (pid,))
            if rc.fetchone(): break
            time.sleep(0.0005)
        lat.append((time.perf_counter() - t0) * 1000)
        time.sleep(0.05)
    res = {"n": n, "mean": sum(lat) / n, "p50": pct(lat, 50), "p95": pct(lat, 95), "p99": pct(lat, 99), "max": max(lat)}
    print(json.dumps(res)); json.dump(res, open(out, "w"), indent=2)
    # a replica must refuse writes
    try:
        rc.execute("INSERT INTO repl_probe DEFAULT VALUES")
        print("ERROR: replica accepted a write!")
    except Exception as e:
        print("replica write rejected (expected):", str(e).strip().splitlines()[0])
