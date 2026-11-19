#!/usr/bin/env python3
"""Turns raw pgbench logs + plan files into summary.csv, summary.md and charts.  Usage: analyze.py <results_dir>"""
import csv, glob, json, math, os, re, sys

R = sys.argv[1] if len(sys.argv) > 1 else "results"
RAW = os.path.join(R, "raw")
SCRIPTS = {"writers": ["ingest", "ack_update", "outbox_relay", "retry_poll"],
           "readers": ["status_read", "monitor"],
           "mix": ["ingest", "ack_update", "status_read"]}
STEPS = [("L0_untuned", "L0 untuned (S0)"), ("L1_indexes", "L1 + indexes (S1)"), ("L2_partitioned", "L2 + partitioning (S2)"),
         ("L3_replica", "L3 + read replica"), ("L4_pooler_replica", "L4 + PgBouncer")]


def pct(sorted_v, p):
    if not sorted_v: return float("nan")
    return sorted_v[max(0, math.ceil(p / 100 * len(sorted_v)) - 1)]


def meta(step):
    d = {}
    f = os.path.join(RAW, step, "meta.txt")
    if os.path.exists(f):
        for kv in open(f).read().split():
            k, v = kv.split("="); d[k] = float(v)
    return d


def load(step):
    """-> {(group, script): [latency_ms,...]} from pgbench -l logs (client tx time script_no epoch us)."""
    out = {}
    for group, names in SCRIPTS.items():
        for f in glob.glob(os.path.join(RAW, step, group + ".[0-9]*")):
            for line in open(f):
                p = line.split()
                if len(p) < 4 or p[2] in ("failed", "skipped"): continue
                try: lat, sno = int(p[2]) / 1000.0, int(p[3])
                except ValueError: continue
                out.setdefault((group, names[sno]), []).append(lat)
    return out


def stats(v, dur):
    s = sorted(v)
    return dict(n=len(s), tps=len(s) / dur if dur else float("nan"), mean=sum(s) / len(s), p50=pct(s, 50),
                p95=pct(s, 95), p99=pct(s, 99), max=s[-1])


rows, table = [], {}
for step in [s for s, _ in STEPS] + ["L2b_partition_aware", "E2_direct_many", "E2_pooled_many"] + sorted(d for d in os.listdir(RAW) if d.startswith("E3_")):
    if not os.path.isdir(os.path.join(RAW, step)): continue
    m = meta(step); dur = m.get("duration", 0)
    data = load(step)
    for (g, name), v in sorted(data.items()):
        st = stats(v, dur); table[(step, name)] = st
        rows.append({"step": step, "group": g, "script": name, **{k: round(x, 3) for k, x in st.items()}})
    allv = [x for v in data.values() for x in v]
    if allv:
        st = stats(allv, dur); table[(step, "ALL")] = st
        rows.append({"step": step, "group": "all", "script": "ALL", **{k: round(x, 3) for k, x in st.items()}})
    if "wal_bytes" in m and allv:
        table[(step, "wal_per_tx")] = m["wal_bytes"] / len(allv)

with open(os.path.join(R, "summary.csv"), "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=["step", "group", "script", "n", "tps", "mean", "p50", "p95", "p99", "max"])
    w.writeheader(); w.writerows(rows)

md = []
def out(s=""): md.append(s)
def flag(st, key):
    """mark percentiles that rest on few samples (p99 needs >=100 samples, p95 >=20 to mean anything)"""
    need = 100 if key == "p99" else 20
    return "\\*" if st["n"] < need else ""

def fm(x, d=2):
    if x != x: return "n/a"
    return f"{x:,.{d}f}" if x < 100 else f"{x:,.0f}"

# ------------------------------------------------------------------ 1. ladder
out("# SIS #3 – measured results\n")
out("All numbers come from `pgbench` per-transaction logs (latency in **ms**), same mixed workload in every step. "
    "Closed-loop clients, one shared CPU core for client + primary (+ replica + pooler), so read them as *relative* gains.\n")
out("## 1. Before/after ladder (cumulative, one change per step)\n")
out("\\* = fewer than 100 samples for p99 (or 20 for p95): the untuned schema is so slow that rare transaction types complete only a handful of times; "
    "treat those cells as indicative and rely on the EXPLAIN ANALYZE comparison in section 4.\n")
out("### Headline: BEFORE (L0 untuned) vs AFTER (L4 = indexes + partitioning + replica + PgBouncer)\n")
out("| Transaction | before p95 ms | after p95 ms | before p99 ms | after p99 ms | p95 gain | p99 gain | before tx/s | after tx/s |")
out("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
for script, label in (("ingest", "Dispatcher ingest (write)"), ("status_read", "Status API read"), ("ack_update", "Provider-ACK update"),
                      ("outbox_relay", "Outbox relay claim"), ("retry_poll", "Retry scheduler poll"), ("monitor", "Monitoring count")):
    a, b = table.get(("L0_untuned", script)), table.get(("L4_pooler_replica", script))
    if a and b:
        out(f"| {label} | {fm(a['p95'])}{flag(a, 'p95')} | {fm(b['p95'])} | {fm(a['p99'])}{flag(a, 'p99')} | {fm(b['p99'])} | {a['p95'] / b['p95']:.0f}x | {a['p99'] / b['p99']:.0f}x | {fm(a['tps'], 1)} | {fm(b['tps'], 1)} |")
out()
for script, title in (("ingest", "Dispatcher ingest transaction (write path, SIS #1 step 2)"),
                      ("status_read", "Status API read `GET /v1/notifications/{id}` (read path)"),
                      ("outbox_relay", "Outbox relay claim"), ("retry_poll", "Retry scheduler poll"),
                      ("ack_update", "Provider-ACK status update"), ("monitor", "Monitoring count (fixed 1-hour window)")):
    out(f"### {title}\n")
    out("| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |")
    out("|---|---:|---:|---:|---:|---:|---:|---:|")
    base = table.get(("L0_untuned", script))
    for step, label in STEPS:
        st = table.get((step, script))
        if not st: continue
        g95 = f"{base['p95'] / st['p95']:.1f}x" if base and st['p95'] else "-"
        g99 = f"{base['p99'] / st['p99']:.1f}x" if base and st['p99'] else "-"
        out(f"| {label} | {st['n']:,} | {fm(st['tps'], 1)} | {fm(st['p50'])} | {fm(st['p95'])}{flag(st, 'p95')} | {fm(st['p99'])}{flag(st, 'p99')} | {g95} | {g99} |")
    out()

out("### Whole workload (all transaction types together)\n")
out("| Step | total tx/s | p95 ms | p99 ms | WAL bytes / tx |")
out("|---|---:|---:|---:|---:|")
for step, label in STEPS:
    st = table.get((step, "ALL"))
    if st: out(f"| {label} | {fm(st['tps'], 1)} | {fm(st['p95'])} | {fm(st['p99'])} | {fm(table.get((step, 'wal_per_tx'), float('nan')), 0)} |")
out()

# ---- partition-aware reads
if ("L2b_partition_aware", "status_read") in table:
    out("### Side experiment: partition-aware status read (S2 schema)\n")
    out("Partitioning by `accepted_at` means `GET /v1/notifications/{id}` (which only knows `event_id`) must probe every partition. "
        "If the id carries its day (UUIDv7 / date-prefixed id), the query can prune to one partition.\n")
    out("| Status read | samples | tps | p50 ms | p95 ms | p99 ms |")
    out("|---|---:|---:|---:|---:|---:|")
    for step, lbl in (("L1_indexes", "S1 (unpartitioned, one index probe)"), ("L2_partitioned", "S2, id only -> probes all partitions"),
                      ("L2b_partition_aware", "S2, id + day -> prunes to one partition")):
        st = table.get((step, "status_read"))
        if st: out(f"| {lbl} | {st['n']:,} | {fm(st['tps'], 1)} | {fm(st['p50'])} | {fm(st['p95'])} | {fm(st['p99'])} |")
    out()

# ---- planning overhead: simple vs prepared
e3 = [("E3_s1_simple", "S1 unpartitioned", "simple"), ("E3_s1_prepared", "S1 unpartitioned", "prepared"),
      ("E3_s2_simple", "S2 partitioned, id only", "simple"), ("E3_s2_prepared", "S2 partitioned, id only", "prepared"),
      ("E3_s2aware_simple", "S2 partitioned, id + day", "simple"), ("E3_s2aware_prepared", "S2 partitioned, id + day", "prepared")]
if any((s_, "status_read") in table for s_, _, _ in e3):
    out("### Planning overhead: simple protocol (re-plans every query) vs prepared statements (plan reused)\n")
    out("Status read only, 4 reader clients and no writers, so the three schema variants are compared without interference.\n")
    out("| Variant | protocol | samples | tx/s | p50 ms | p95 ms | p99 ms |")
    out("|---|---|---:|---:|---:|---:|---:|")
    for step, lbl, mode in e3:
        st = table.get((step, "status_read"))
        if st: out(f"| {lbl} | {mode} | {st['n']:,} | {fm(st['tps'], 0)} | {fm(st['p50'])} | {fm(st['p95'])} | {fm(st['p99'])} |")
    out()

# ---- CPU accounting
cpu_rows = []
for step, label in STEPS:
    m = meta(step)
    if "cpu_primary" in m and m.get("cpu_window"):
        d = m["cpu_window"]
        cpu_rows.append((label, 100 * m["cpu_primary"] / d, 100 * m.get("cpu_replica", 0) / d, 100 * m.get("cpu_pooler", 0) / d))
if cpu_rows:
    out("### Where the CPU went (percent of ONE core, averaged over the measured run)\n")
    out("This sandbox has a single core, so everything competes for it. The useful signal is *which process* does the work.\n")
    out("| Step | primary | replica | PgBouncer | total DB-side |")
    out("|---|---:|---:|---:|---:|")
    for label, p, r, b in cpu_rows:
        out(f"| {label} | {p:.0f}% | {r:.0f}% | {b:.0f}% | {p + r + b:.0f}% |")
    out()

# ------------------------------------------------------------------ 2. pooling
if ("E2_direct_many", "ALL") in table:
    out("## 2. Connection pooling (many concurrent client connections, S2 schema)\n")
    out("| Mode | client connections | PostgreSQL backends | tx/s | p50 ms | p95 ms | p99 ms |")
    out("|---|---:|---:|---:|---:|---:|---:|")
    for step, label in (("E2_direct_many", "direct to PostgreSQL"), ("E2_pooled_many", "via PgBouncer (transaction pooling)")):
        st = table.get((step, "ALL"))
        bf = os.path.join(RAW, step, "backends.txt")
        be = open(bf).read().strip() if os.path.exists(bf) else "?"
        cl = int(meta(step).get("clients", 0))
        if st: out(f"| {label} | {cl} | {be} | {fm(st['tps'], 1)} | {fm(st['p50'])} | {fm(st['p95'])} | {fm(st['p99'])} |")
    out()
    for sc in ("ingest", "status_read", "ack_update"):
        a, b = table.get(("E2_direct_many", sc)), table.get(("E2_pooled_many", sc))
        if a and b:
            out(f"- `{sc}`: p95 {fm(a['p95'])} → {fm(b['p95'])} ms, p99 {fm(a['p99'])} → {fm(b['p99'])} ms")
    out()
cc = os.path.join(R, "connect_cost.json")
if os.path.exists(cc):
    j = json.load(open(cc))
    out("### Cost of connect → query → disconnect (an app *without* a pool pays this on every request)\n")
    out("| Path | mean ms | p50 ms | p95 ms | p99 ms |")
    out("|---|---:|---:|---:|---:|")
    for k, lbl in (("direct", "new connection straight to PostgreSQL"), ("pgbouncer", "new client connection to PgBouncer (server connection reused)")):
        out(f"| {lbl} | {fm(j[k]['mean'], 3)} | {fm(j[k]['p50'], 3)} | {fm(j[k]['p95'], 3)} | {fm(j[k]['p99'], 3)} |")
    out()

# ------------------------------------------------------------------ 3. replica
out("## 3. Read replica\n")
vj = os.path.join(R, "replica_visibility.json")
if os.path.exists(vj):
    v = json.load(open(vj))
    out(f"**Replication visibility delay** (commit on primary → row readable on replica, measured under full benchmark load, {v['n']} probes): "
        f"mean {v['mean']:.1f} ms, p50 {v['p50']:.1f} ms, p95 {v['p95']:.1f} ms, p99 {v['p99']:.1f} ms, max {v['max']:.1f} ms.\n")
lagf = os.path.join(R, "replica_lag_samples.csv")
if os.path.exists(lagf):
    lag = [float(l.split(",")[1]) for l in open(lagf) if l.count(",") == 2 and l.split(",")[1].replace(".", "").isdigit()]
    byt = [float(l.split(",")[2]) for l in open(lagf) if l.count(",") == 2 and l.strip().split(",")[2].lstrip("-").isdigit()]
    if lag:
        s = sorted(lag); sb = sorted(byt) if byt else [0]
        out(f"**`pg_stat_replication` samples (1 per second during the run):** replay_lag p50 {pct(s, 50):.1f} ms, p95 {pct(s, 95):.1f} ms, "
            f"max {s[-1]:.1f} ms; unreplayed WAL p95 {pct(sb, 95) / 1024:.0f} KiB, max {sb[-1] / 1024:.0f} KiB ({len(s)} samples).\n")
vt = os.path.join(R, "replica_visibility.txt")
if os.path.exists(vt):
    for l in open(vt):
        if "rejected" in l or "accepted" in l: out(f"Write attempt on the replica: `{l.strip()}`\n")

# ------------------------------------------------------------------ 4. plans
def parse_plans(path):
    res = {}
    cur = None
    for line in open(path):
        m = re.match(r"=== (Q\d[b]?) (.*)", line)
        if m: cur = m.group(1); res[cur] = {"title": m.group(2), "scans": [], "time": None, "buffers": None, "pruned": None, "plan": None, "text": []}; continue
        if cur is None: continue
        res[cur]["text"].append(line.rstrip("\n"))
        m = re.search(r"(Seq Scan|Index Only Scan|Index Scan|Bitmap Heap Scan) (?:using (\S+) )?on (\S+)", line)
        if m:
            kind = m.group(1) + (f" ({m.group(2)})" if m.group(2) else "")
            tbl = re.sub(r"_p\d{8}", "_pYYYYMMDD", m.group(3))
            key = f"{kind} on {tbl}"
            if key not in res[cur]["scans"]: res[cur]["scans"].append(key)
        m = re.search(r"Planning Time: ([\d.]+) ms", line)
        if m: res[cur]["plan"] = float(m.group(1))
        m = re.search(r"Execution Time: ([\d.]+) ms", line)
        if m: res[cur]["time"] = float(m.group(1))
        m = re.search(r"Buffers: shared (.*)", line)
        if m and res[cur]["buffers"] is None:
            h = re.search(r"hit=(\d+)", m.group(1)); rd = re.search(r"read=(\d+)", m.group(1))
            res[cur]["buffers"] = int(h.group(1) if h else 0) + int(rd.group(1) if rd else 0)
        m = re.search(r"Subplans Removed: (\d+)", line)
        if m: res[cur]["pruned"] = int(m.group(1))
    return res

plans = {}
for v in ("s0", "s1", "s2"):
    p = os.path.join(R, "plans", v + ".txt")
    if os.path.exists(p): plans[v] = parse_plans(p)
if plans:
    out("## 4. EXPLAIN (ANALYZE, BUFFERS) before → after\n")
    out("*exec* = server-side execution time of one run on the seeded dataset, *plan* = planning time (matters for tiny queries), *pages* = shared buffers touched (hit + read).\n")
    out("| Query | S0 untuned | S1 + indexes | S2 + partitioning |")
    out("|---|---|---|---|")
    for q in ["Q1", "Q2", "Q2b", "Q3", "Q4", "Q5", "Q6", "Q7"]:
        cells = []
        title = ""
        for v in ("s0", "s1", "s2"):
            d = plans.get(v, {}).get(q)
            if not d: cells.append("-"); continue
            title = d["title"]
            scans = "; ".join(d["scans"][:2]) + (f" (+{len(d['scans']) - 2} more)" if len(d["scans"]) > 2 else "")
            extra = f", {d['pruned']} partitions pruned" if d["pruned"] else ""
            cells.append(f"exec **{d['time']:.3f} ms** + plan {d['plan'] or 0:.3f} ms, {d['buffers']:,} pages{extra}<br>{scans}")
        out(f"| {q} {title} | " + " | ".join(cells) + " |")
    out()

# ------------------------------------------------------------------ 5. sizes / retention
for v, lbl in (("s0", "S0"), ("s1", "S1"), ("s2", "S2")):
    p = os.path.join(R, f"sizes_{v}.txt")
    if os.path.exists(p):
        out(f"### Sizes {lbl}\n```\n{open(p).read().strip()}\n```\n")

def retention(path):
    if not os.path.exists(path): return None
    txt = open(path).read().splitlines(); res = []; last = None
    for l in txt:
        if l.startswith(">>> "): last = l[4:].strip()
        m = re.match(r"^Time: ([\d.]+) ms", l)
        if m and last: res.append((last, float(m.group(1)))); last = None
    wal = None
    for i, l in enumerate(txt):
        if "wal_bytes" in l and i + 2 < len(txt):
            m = re.search(r"\|\s*(\d+)\s*$", txt[i + 2])
            if m: wal = int(m.group(1))
    dead = {}
    for l in txt:
        m = re.match(r"^\s*(deliveries|notification_events|outbox)\s*\|\s*(\d+)\s*$", l)
        if m: dead[m.group(1)] = int(m.group(2))
    return res, wal, dead

out("## 5. Retention: removing the oldest day (≈1/7 of the data)\n")
rd, rp = retention(os.path.join(R, "retention_delete.txt")), retention(os.path.join(R, "retention_drop.txt"))
out("| Method | operation | time |")
out("|---|---|---:|")
if rd:
    for op, t in rd[0]: out(f"| unpartitioned | {op} | {t / 1000:.2f} s |")
    tot = sum(t for _, t in rd[0]) / 1000
    out(f"| **unpartitioned total** | DELETE + VACUUM | **{tot:.2f} s**; WAL written by the DELETEs: **{rd[1] / 1e6:.0f} MB** |")
if rp:
    for op, t in rp[0]: out(f"| partitioned | {op} | {t:.1f} ms |")
    tot = sum(t for _, t in rp[0])
    out(f"| **partitioned total** | DETACH + DROP | **{tot:.0f} ms**; WAL written: **{(rp[1] or 0) / 1e3:.0f} KB**; no dead rows, nothing to vacuum |")
    if rd and tot: out(f"| **speed-up** | | **{sum(t for _, t in rd[0]) / tot:.0f}x faster, {rd[1] / max(rp[1], 1):.0f}x less WAL** |")
out()

open(os.path.join(R, "summary.md"), "w").write("\n".join(md))

# ------------------------------------------------------------------ charts
try:
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    os.makedirs(os.path.join(R, "charts"), exist_ok=True)
    present = [(s, l) for s, l in STEPS if (s, "ingest") in table]
    for script, title, fname in (("ingest", "Ingest transaction latency (write path)", "ingest"),
                                 ("status_read", "Status API read latency (read path)", "status_read"),
                                 ("outbox_relay", "Outbox relay claim latency", "outbox_relay"),
                                 ("retry_poll", "Retry scheduler poll latency", "retry_poll")):
        pr = [(s, l) for s, l in present if (s, script) in table]
        if not pr: continue
        fig, ax = plt.subplots(figsize=(9, 4.2))
        xs = range(len(pr)); w = 0.38
        p95 = [table[(s, script)]["p95"] for s, _ in pr]; p99 = [table[(s, script)]["p99"] for s, _ in pr]
        b1 = ax.bar([x - w / 2 for x in xs], p95, w, label="p95", color="#4c78a8")
        b2 = ax.bar([x + w / 2 for x in xs], p99, w, label="p99", color="#f58518")
        ax.set_yscale("log"); ax.set_ylabel("latency, ms (log scale)"); ax.set_title(title)
        ax.set_xticks(list(xs)); ax.set_xticklabels([l.replace(" (", "\n(").replace(" + ", "\n+ ") for _, l in pr], fontsize=8)
        for b in list(b1) + list(b2):
            ax.annotate(f"{b.get_height():,.0f}" if b.get_height() >= 100 else f"{b.get_height():.1f}", (b.get_x() + b.get_width() / 2, b.get_height()), ha="center", va="bottom", fontsize=7)
        ax.legend(); ax.grid(axis="y", alpha=0.3, which="both"); fig.tight_layout()
        fig.savefig(os.path.join(R, "charts", f"{fname}_p95_p99.png"), dpi=130); plt.close(fig)
    pr = [(s, l) for s, l in present if (s, "ALL") in table]
    if pr:
        fig, ax = plt.subplots(figsize=(9, 4.2))
        ax.bar(range(len(pr)), [table[(s, "ALL")]["tps"] for s, _ in pr], color="#54a24b")
        ax.set_xticks(range(len(pr))); ax.set_xticklabels([l.replace(" (", "\n(").replace(" + ", "\n+ ") for _, l in pr], fontsize=8)
        ax.set_ylabel("transactions / second"); ax.set_title("Total throughput, same mixed workload"); ax.grid(axis="y", alpha=0.3)
        for i, (s, _) in enumerate(pr): ax.annotate(f"{table[(s, 'ALL')]['tps']:,.0f}", (i, table[(s, 'ALL')]["tps"]), ha="center", va="bottom", fontsize=8)
        fig.tight_layout(); fig.savefig(os.path.join(R, "charts", "throughput.png"), dpi=130); plt.close(fig)
except Exception as e:
    print("charts skipped:", e)
print("wrote", os.path.join(R, "summary.md"))
