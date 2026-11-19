#!/usr/bin/env bash
# PgBouncer in TRANSACTION pooling mode. Two logical databases: notif (primary) and notif_ro (replica).
set -euo pipefail
source "$(dirname "$0")/../config.sh"
mkdir -p "$SIS3_HOME/pgbouncer" "$SOCKDIR"
printf '"%s" ""\n' "$(whoami)" > "$SIS3_HOME/pgbouncer/userlist.txt"   # PgBouncer needs the user listed even with auth_type=trust
cat > "$SIS3_HOME/pgbouncer/pgbouncer.ini" <<INI
[databases]
$DB = host=127.0.0.1 port=$PRIMARY_PORT dbname=$DB
${DB}_ro = host=127.0.0.1 port=$REPLICA_PORT dbname=$DB

[pgbouncer]
listen_addr = 127.0.0.1
listen_port = $POOLER_PORT
unix_socket_dir = $SOCKDIR
auth_type = trust
auth_file = $SIS3_HOME/pgbouncer/userlist.txt
pool_mode = transaction
default_pool_size = $POOL_SIZE
max_client_conn = 1000
ignore_startup_parameters = extra_float_digits
logfile = $SIS3_HOME/pgbouncer/pgbouncer.log
pidfile = $SIS3_HOME/pgbouncer/pgbouncer.pid
INI
if [ -f "$SIS3_HOME/pgbouncer/pgbouncer.pid" ] && kill -0 "$(cat "$SIS3_HOME/pgbouncer/pgbouncer.pid")" 2>/dev/null; then
  kill "$(cat "$SIS3_HOME/pgbouncer/pgbouncer.pid")"; sleep 1
fi
pgbouncer -d "$SIS3_HOME/pgbouncer/pgbouncer.ini"
sleep 1
echo "pgbouncer listening on :$POOLER_PORT (pool_size=$POOL_SIZE, transaction mode)"
