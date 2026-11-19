#!/usr/bin/env bash
# Shared functions for run_all.sh
source "$(dirname "${BASH_SOURCE[0]}")/../config.sh"
RAW="$RESULTS/raw"; PLANS="$RESULTS/plans"
mkdir -p "$RAW" "$PLANS"
log() { echo "[$(date +%H:%M:%S)] $*"; }

recreate_db() {
  dropdb -h 127.0.0.1 -p "$PRIMARY_PORT" --force --if-exists "$DB"
  createdb -h 127.0.0.1 -p "$PRIMARY_PORT" "$DB"
  psqlp -c "ALTER DATABASE $DB SET timezone = 'UTC'"
  psqlp -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"
}

seed() { psqlp -v n="$N" -v anchor="$ANCHOR" -f "$PROJECT_DIR/sql/seed.sql" 2>&1 | grep -E "^(seeding|vacuum)" ; }

sizes() {  # table + index sizes of the three big tables, summed over partitions
  psqlp -c "SELECT regexp_replace(relname, '_p[0-9]{8}\$', '') AS \"table\",
                   pg_size_pretty(sum(pg_table_size(oid))::bigint)   AS table_size,
                   pg_size_pretty(sum(pg_indexes_size(oid))::bigint) AS index_size,
                   pg_size_pretty(sum(pg_total_relation_size(oid))::bigint) AS total
              FROM pg_class
             WHERE relkind = 'r' AND relname ~ '^(notification_events|deliveries|outbox)(_p[0-9]{8})?\$'
             GROUP BY 1 ORDER BY 1" > "$RESULTS/sizes_$1.txt"
}

explain_plans() { psqlp -v n="$N" -v anchor="$ANCHOR" -f "$PROJECT_DIR/sql/explain.sql" > "$PLANS/$1.txt" 2>&1; }

# CPU accounting: "pid cpu_seconds" for the postmaster and all its processes (Linux /proc: exact; macOS: ps, 1 s resolution).
# Two snapshots taken in the MIDDLE of a run (clients connected) are diffed per pid, so short-lived backends are not lost.
cpu_snap() {
  local pm; pm=$(head -1 "$1/postmaster.pid" 2>/dev/null) || return 0
  local tck; tck=$(getconf CLK_TCK 2>/dev/null || echo 100)
  for p in $pm $(pgrep -P "$pm" || true); do
    if [ -r "/proc/$p/stat" ]; then
      cut -d')' -f2 "/proc/$p/stat" 2>/dev/null | awk -v p="$p" -v t="$tck" '{printf "%s %.3f\n", p, ($12 + $13) / t}'
    else
      ps -o cputime= -p "$p" 2>/dev/null | awk -v p="$p" '{n = split($1, a, ":"); s = 0; for (i = 1; i <= n; i++) s = s * 60 + a[i]; printf "%s %.3f\n", p, s}'
    fi
  done || true
  return 0
}
cpu_delta() { awk 'NR == FNR {a[$1] = $2; next} ($1 in a) {s += $2 - a[$1]} END {printf "%.2f", s}' "$1" "$2"; }
cpu_pooler() {
  local pid; pid=$(cat "$SIS3_HOME/pgbouncer/pgbouncer.pid" 2>/dev/null) || { echo "$pid 0"; return; }
  cpu_snap_pid "$pid"
}
cpu_snap_pid() {
  local p=$1
  if [ -r "/proc/$p/stat" ]; then cut -d')' -f2 "/proc/$p/stat" | awk -v p="$p" -v t="$(getconf CLK_TCK 2>/dev/null || echo 100)" '{printf "%s %.3f\n", p, ($12 + $13) / t}'
  else ps -o cputime= -p "$p" | awk -v p="$p" '{n = split($1, a, ":"); s = 0; for (i = 1; i <= n; i++) s = s * 60 + a[i]; printf "%s %.3f\n", p, s}'; fi
}

# ---- the standard mixed workload ------------------------------------------------------------
# writers (dispatcher/worker/relay/retry) + readers (status API / monitoring) run at the same time.
#   run_pair NAME WRITER_PORT READER_PORT INGEST_SCRIPT SECONDS WRITER_CLIENTS READER_CLIENTS [WRITER_DB] [READER_DB]
run_pair() {
  local name=$1 wport=$2 rport=$3 ing=$4 dur=$5 wc=$6 rc=$7 wdb=${8:-$DB} rdb=${9:-$DB}
  local out="$RAW/$name"; rm -rf "$out"; mkdir -p "$out"
  local common=(-n -M simple -h 127.0.0.1 -j 1 -D "N=$N" -D "anchor=$ANCHOR")
  local W=(-f "$PROJECT_DIR/bench/$ing@60" -f "$PROJECT_DIR/bench/update.sql@30" -f "$PROJECT_DIR/bench/relay.sql@6" -f "$PROJECT_DIR/bench/retry.sql@4")
  local rs="${READER_SCRIPT:-read_status.sql}"
  local R=(-f "$PROJECT_DIR/bench/$rs@95" -f "$PROJECT_DIR/bench/monitor.sql@5")
  log "  [$name] warm-up ${WARMUP}s"
  pgbench "${common[@]}" -p "$wport" -c "$wc" -T "$WARMUP" "${W[@]}" "$wdb" >/dev/null 2>&1 & local p1=$!
  pgbench "${common[@]}" -p "$rport" -c "$rc" -T "$WARMUP" "${R[@]}" "$rdb" >/dev/null 2>&1 & local p2=$!
  wait $p1 $p2 || true
  log "  [$name] measuring ${dur}s  (writers=$wc via :$wport, readers=$rc via :$rport)"
  local lsn0; lsn0=$(psqlp -At -c "SELECT pg_current_wal_lsn()")
  pgbench "${common[@]}" -p "$wport" -c "$wc" -T "$dur" -r -l --log-prefix="$out/writers" "${W[@]}" "$wdb" > "$out/writers.txt" 2>&1 & p1=$!
  pgbench "${common[@]}" -p "$rport" -c "$rc" -T "$dur" -r -l --log-prefix="$out/readers" "${R[@]}" "$rdb" > "$out/readers.txt" 2>&1 & p2=$!
  local cw=$((dur - 16))
  ( set +e; sleep 8; cpu_snap "$SIS3_HOME/primary" > "$out/cpu_p1"; cpu_snap "$SIS3_HOME/replica" > "$out/cpu_r1"; cpu_pooler > "$out/cpu_b1" 2>/dev/null
    sleep "$cw"; cpu_snap "$SIS3_HOME/primary" > "$out/cpu_p2"; cpu_snap "$SIS3_HOME/replica" > "$out/cpu_r2"; cpu_pooler > "$out/cpu_b2" 2>/dev/null ) & local sp=$!
  wait $p1 $p2 $sp || true
  local wal; wal=$(psqlp -At -c "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '$lsn0')")
  echo "duration=$dur wal_bytes=$wal writer_clients=$wc reader_clients=$rc cpu_primary=$(cpu_delta "$out/cpu_p1" "$out/cpu_p2") cpu_replica=$(cpu_delta "$out/cpu_r1" "$out/cpu_r2") cpu_pooler=$(cpu_delta "$out/cpu_b1" "$out/cpu_b2") cpu_window=$cw" > "$out/meta.txt"
  grep -E "failed transactions" "$out"/writers.txt "$out"/readers.txt | head -2 | sed 's/^/    /'
}

# ---- one process, many clients (connection-pooling experiment) -----------------------------------
#   run_single NAME PORT DB CLIENTS SECONDS
run_single() {
  local name=$1 port=$2 db=$3 clients=$4 dur=$5
  local out="$RAW/$name"; rm -rf "$out"; mkdir -p "$out"
  local args=(-n -M simple -h 127.0.0.1 -p "$port" -j 2 -D "N=$N" -D "anchor=$ANCHOR" -c "$clients"
              -f "$PROJECT_DIR/bench/ingest.sql@40" -f "$PROJECT_DIR/bench/update.sql@30" -f "$PROJECT_DIR/bench/read_status.sql@30")
  log "  [$name] warm-up ${WARMUP}s, $clients clients via :$port"
  pgbench "${args[@]}" -T "$WARMUP" "$db" >/dev/null 2>&1
  log "  [$name] measuring ${dur}s"
  # sample, in the middle of the run, how many PostgreSQL backend processes exist
  ( sleep $((dur / 2)); psqlp -At -c "SELECT count(*) FROM pg_stat_activity WHERE datname = '$DB' AND backend_type = 'client backend'" > "$out/backends.txt" ) & local bp=$!
  pgbench "${args[@]}" -T "$dur" -r -l --log-prefix="$out/mix" "$db" > "$out/mix.txt" 2>&1
  wait $bp
  echo "duration=$dur clients=$clients" > "$out/meta.txt"
  grep -E "failed transactions" "$out/mix.txt" | head -1 | sed 's/^/    /'
  echo "    PostgreSQL client backends mid-run: $(cat "$out/backends.txt")"
}

# ---- readers only, to compare simple vs prepared protocol (planning-overhead experiment) -------------
#   run_readers NAME simple|prepared SCRIPT SECONDS
run_readers() {
  local name=$1 mode=$2 script=$3 dur=$4
  local out="$RAW/$name"; rm -rf "$out"; mkdir -p "$out"
  local args=(-n -M "$mode" -h 127.0.0.1 -p "$PRIMARY_PORT" -j 1 -c "$READ_CLIENTS" -D "N=$N" -D "anchor=$ANCHOR" -f "$PROJECT_DIR/bench/$script")
  log "  [$name] $mode protocol, $READ_CLIENTS readers, ${dur}s"
  pgbench "${args[@]}" -T 5 "$DB" >/dev/null 2>&1
  pgbench "${args[@]}" -T "$dur" -r -l --log-prefix="$out/readers" "$DB" > "$out/readers.txt" 2>&1
  echo "duration=$dur" > "$out/meta.txt"
}
