#!/usr/bin/env bash
# Full SIS #3 experiment: S0 (untuned) -> S1 (+indexes) -> S2 (+partitioning) -> +read replica -> +PgBouncer.
# Takes ~20 minutes with the default N=2,000,000. Output: results/  (raw logs, plans, tables, charts)
#
# Stages (run them all, or one at a time if your machine is flaky):
#   STAGES="s0 s1"   untuned schema, then indexes on the same data   (~9 min)
#   STAGES="s2"      partitioned schema + replica + PgBouncer + pooling experiments (~10 min)
#   STAGES="analyze" build results/summary.md and the charts
# Default: STAGES="s0 s1 s2 analyze"
set -euo pipefail
source "$(dirname "$0")/lib.sh"
STAGES="${STAGES:-s0 s1 s2 analyze}"
has() { [[ " $STAGES " == *" $1 "* ]]; }
ANCHOR=$(anchor_epoch); export ANCHOR
echo "$ANCHOR" > "$RESULTS/anchor.txt"
log "stages: $STAGES   anchor (midnight UTC) = $ANCHOR   N=$N   clients: writers=$WRITE_CLIENTS readers=$READ_CLIENTS"

# start clean: no stale replica from an earlier run
pg_ctl -D "$SIS3_HOME/replica" -m fast stop >/dev/null 2>&1 || true
rm -rf "$SIS3_HOME/replica"
"$PROJECT_DIR/scripts/setup_primary.sh" >/dev/null
"$PROJECT_DIR/scripts/setup_pooler.sh"  >/dev/null
psql -h 127.0.0.1 -p "$PRIMARY_PORT" -d postgres -X -q -c "ALTER SYSTEM SET max_wal_size = '4GB'" -c "SELECT pg_reload_conf()" >/dev/null

if has s0; then
  # =============================== S0: untuned baseline ===============================
  log "=== S0: untuned schema (primary keys only) ==="
  recreate_db
  psqlp -f "$PROJECT_DIR/sql/schema_s0_untuned.sql" >/dev/null
  seed; sizes s0
  explain_plans s0
  psqlp -c "SELECT pg_stat_statements_reset()" >/dev/null
  run_pair L0_untuned "$PRIMARY_PORT" "$PRIMARY_PORT" ingest_s0.sql "$DURATION_S0" "$WRITE_CLIENTS" "$READ_CLIENTS"
  psqlp -c "SELECT left(regexp_replace(query, '\s+', ' ', 'g'), 90) AS query, calls, round(mean_exec_time::numeric, 2) AS mean_ms,
                   round(total_exec_time::numeric / 1000, 1) AS total_s, round((100 * total_exec_time / sum(total_exec_time) OVER ())::numeric, 1) AS pct
              FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$DB')
             ORDER BY total_exec_time DESC LIMIT 8" > "$RESULTS/pg_stat_statements_s0.txt"
fi

if has s1; then
  # =============================== S1: + indexes (same data as S0) ===============================
  log "=== S1: + indexes (same data) ==="
  psqlp -f "$PROJECT_DIR/sql/schema_s1_indexes.sql" >/dev/null
  sizes s1
  explain_plans s1
  psqlp -f "$PROJECT_DIR/sql/index_size.sql" > "$PLANS/index_size_s1.txt" 2>&1
  run_pair L1_indexes "$PRIMARY_PORT" "$PRIMARY_PORT" ingest.sql "$DURATION" "$WRITE_CLIENTS" "$READ_CLIENTS"
  log "planning-overhead experiment on S1 (readers only)"
  run_readers E3_s1_simple   simple   read_status.sql 20
  run_readers E3_s1_prepared prepared read_status.sql 20
  log "retention on the unpartitioned schema (DELETE + VACUUM)"
  psqlp -v anchor="$ANCHOR" -f "$PROJECT_DIR/sql/retention_delete.sql" > "$RESULTS/retention_delete.txt" 2>&1
fi

if has s2; then
  # =============================== S2: + partitioning ===============================
  log "=== S2: + daily range partitioning ==="
  recreate_db
  psqlp -v anchor="$ANCHOR" -f "$PROJECT_DIR/sql/schema_s2_partitioned.sql" >/dev/null
  seed; sizes s2
  explain_plans s2
  run_pair L2_partitioned "$PRIMARY_PORT" "$PRIMARY_PORT" ingest.sql "$DURATION" "$WRITE_CLIENTS" "$READ_CLIENTS"
  log "side experiment: partition-aware status reads (id carries its day -> prune to one partition)"
  READER_SCRIPT=read_status_pruned.sql run_pair L2b_partition_aware "$PRIMARY_PORT" "$PRIMARY_PORT" ingest.sql "$DURATION" "$WRITE_CLIENTS" "$READ_CLIENTS"
  log "planning-overhead experiment on S2 (readers only)"
  run_readers E3_s2_simple         simple   read_status.sql        20
  run_readers E3_s2_prepared       prepared read_status.sql        20
  run_readers E3_s2aware_simple    simple   read_status_pruned.sql 20
  run_readers E3_s2aware_prepared  prepared read_status_pruned.sql 20

  # =============================== + read replica ===============================
  log "=== + streaming read replica (reads go to the replica) ==="
  "$PROJECT_DIR/scripts/setup_replica.sh"
  for i in $(seq 1 60); do   # wait until the replica has replayed everything
    lag=$(psqlp -At -c "SELECT coalesce(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn), -1) FROM pg_stat_replication")
    [ "${lag:-1}" != "-1" ] && [ "${lag:-1}" -lt 100000 ] && break; sleep 2
  done
  ( while :; do psqlp -At -F, -c "SELECT extract(epoch from now())::bigint, coalesce(extract(epoch from replay_lag) * 1000, 0), pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) FROM pg_stat_replication"; sleep 1; done > "$RESULTS/replica_lag_samples.csv" 2>/dev/null ) &
  SAMPLER=$!
  ( sleep $((WARMUP + 5)); python3 "$PROJECT_DIR/scripts/replica_visibility.py" "$PRIMARY_PORT" "$REPLICA_PORT" "$DB" 300 "$RESULTS/replica_visibility.json" > "$RESULTS/replica_visibility.txt" 2>&1 ) &
  VIS=$!
  run_pair L3_replica "$PRIMARY_PORT" "$REPLICA_PORT" ingest.sql "$DURATION" "$WRITE_CLIENTS" "$READ_CLIENTS"
  wait $VIS || true
  kill $SAMPLER 2>/dev/null || true

  # =============================== + connection pooling ===============================
  log "=== + PgBouncer (writers -> pooler -> primary, readers -> pooler -> replica) ==="
  run_pair L4_pooler_replica "$POOLER_PORT" "$POOLER_PORT" ingest.sql "$DURATION" "$WRITE_CLIENTS" "$READ_CLIENTS" "$DB" "${DB}_ro"
  log "=== pooling experiment: $HIGH_CLIENTS concurrent client connections, all on the primary ==="
  run_single E2_direct_many   "$PRIMARY_PORT" "$DB" "$HIGH_CLIENTS" "$DURATION"
  run_single E2_pooled_many   "$POOLER_PORT"  "$DB" "$HIGH_CLIENTS" "$DURATION"
  log "connect-per-request cost (what an app without a pool pays)"
  python3 "$PROJECT_DIR/scripts/connect_cost.py" "$PRIMARY_PORT" "$POOLER_PORT" "$DB" 1000 "$RESULTS/connect_cost.json" | tee "$RESULTS/connect_cost.txt"

  # =============================== retention on partitions ===============================
  log "retention on the partitioned schema (DETACH + DROP)"
  psqlp -v anchor="$ANCHOR" -f "$PROJECT_DIR/sql/retention_drop.sql" > "$RESULTS/retention_drop.txt" 2>&1
fi

if has analyze; then
  log "=== analysis ==="
  python3 "$PROJECT_DIR/analysis/analyze.py" "$RESULTS"
fi
log "DONE (stages: $STAGES). See $RESULTS/summary.md"
