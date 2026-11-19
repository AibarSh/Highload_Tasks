#!/usr/bin/env bash
# Creates and starts the PRIMARY cluster (port 5432). Safe to re-run.
set -euo pipefail
source "$(dirname "$0")/../config.sh"
mkdir -p "$SIS3_HOME" "$SOCKDIR" "$RESULTS"
if [ ! -d "$SIS3_HOME/primary" ]; then
  initdb -D "$SIS3_HOME/primary" --auth=trust --encoding=UTF8 >/dev/null
  cat >> "$SIS3_HOME/primary/postgresql.conf" <<CONF
# ---- SIS #3 settings (identical for every benchmark step so only ONE thing changes at a time) ----
port = $PRIMARY_PORT
listen_addresses = '127.0.0.1'
unix_socket_directories = '$SOCKDIR'
max_connections = 400
shared_buffers = 512MB
effective_cache_size = 2GB
work_mem = 16MB
maintenance_work_mem = 256MB
wal_level = replica
max_wal_senders = 5
max_wal_size = 4GB
checkpoint_timeout = 5min
synchronous_commit = on          # durability is NOT traded for speed
track_io_timing = on
timezone = 'UTC'
shared_preload_libraries = 'pg_stat_statements'
CONF
fi
pg_ctl -D "$SIS3_HOME/primary" -l "$SIS3_HOME/primary.log" -w start >/dev/null || true
psql -h 127.0.0.1 -p "$PRIMARY_PORT" -d postgres -X -q -At -c "SELECT version();"
