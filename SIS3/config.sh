#!/usr/bin/env bash
# Central configuration. Override any value from the environment, e.g.  N=500000 DURATION=20 ./scripts/run_all.sh
#   macOS (Homebrew):  PGBIN=$(brew --prefix postgresql@16)/bin
export PGBIN="${PGBIN:-/usr/lib/postgresql/16/bin}"
export SIS3_HOME="${SIS3_HOME:-$HOME/sis3-data}"       # where the clusters and logs live
export SOCKDIR="${SOCKDIR:-/tmp/sis3-sock}"
export PRIMARY_PORT="${PRIMARY_PORT:-5432}"
export REPLICA_PORT="${REPLICA_PORT:-5433}"
export POOLER_PORT="${POOLER_PORT:-6432}"
export DB="${DB:-notif}"

export N="${N:-2000000}"                  # seeded deliveries (SIS #1 estimate is 259M/day; this is a scaled-down 7-day window)
export WARMUP="${WARMUP:-10}"             # seconds, not recorded
export DURATION="${DURATION:-45}"         # seconds per measured run
export DURATION_S0="${DURATION_S0:-180}"   # the untuned schema is slow, so it gets a longer run to collect enough samples
export WRITE_CLIENTS="${WRITE_CLIENTS:-8}"
export READ_CLIENTS="${READ_CLIENTS:-4}"
export HIGH_CLIENTS="${HIGH_CLIENTS:-200}" # connection-pooling experiment
export POOL_SIZE="${POOL_SIZE:-20}"

export PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export RESULTS="${RESULTS:-$PROJECT_DIR/results}"
export PATH="$PGBIN:$PATH"

# helpers
psqlp() { psql -h 127.0.0.1 -p "$PRIMARY_PORT" -d "$DB" -X -q -v ON_ERROR_STOP=1 "$@"; }
psqlr() { psql -h 127.0.0.1 -p "$REPLICA_PORT" -d "$DB" -X -q -v ON_ERROR_STOP=1 "$@"; }
anchor_epoch() { date -u -d "today 00:00" +%s 2>/dev/null || date -u -j -f "%Y-%m-%d %H:%M:%S" "$(date -u +%Y-%m-%d) 00:00:00" +%s; }
