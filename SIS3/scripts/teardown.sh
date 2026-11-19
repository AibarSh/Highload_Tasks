#!/usr/bin/env bash
source "$(dirname "$0")/../config.sh"
[ -f "$SIS3_HOME/pgbouncer/pgbouncer.pid" ] && kill "$(cat "$SIS3_HOME/pgbouncer/pgbouncer.pid")" 2>/dev/null
pg_ctl -D "$SIS3_HOME/replica" -m fast stop 2>/dev/null
pg_ctl -D "$SIS3_HOME/primary" -m fast stop 2>/dev/null
echo "stopped. Data is still in $SIS3_HOME (delete it to start from scratch)."
