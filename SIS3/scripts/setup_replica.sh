#!/usr/bin/env bash
# Builds a hot-standby REPLICA (port 5433) from the primary with pg_basebackup + streaming replication.
set -euo pipefail
source "$(dirname "$0")/../config.sh"
if [ -d "$SIS3_HOME/replica" ]; then
  pg_ctl -D "$SIS3_HOME/replica" -m fast -w stop >/dev/null 2>&1 || true
  rm -rf "$SIS3_HOME/replica"
fi
pg_basebackup -h 127.0.0.1 -p "$PRIMARY_PORT" -D "$SIS3_HOME/replica" -R -X stream -c fast
cat >> "$SIS3_HOME/replica/postgresql.conf" <<CONF
port = $REPLICA_PORT
hot_standby = on
CONF
chmod 700 "$SIS3_HOME/replica"
pg_ctl -D "$SIS3_HOME/replica" -l "$SIS3_HOME/replica.log" -w start >/dev/null
sleep 2
psql -h 127.0.0.1 -p "$REPLICA_PORT" -d postgres -X -At -c "SELECT 'replica in_recovery=' || pg_is_in_recovery();"
psql -h 127.0.0.1 -p "$PRIMARY_PORT" -d postgres -X -At -c "SELECT 'primary sees replica: ' || state || ' / ' || sync_state FROM pg_stat_replication;"
