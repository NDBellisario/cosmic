#!/usr/bin/env bash
# Start the self-owned MySQL instance for Cosmic.
#
# Homebrew's `brew services start mysql` is broken on this machine (launchd
# error 78, and there's no passwordless sudo to fix the _mysql-owned datadir),
# so we run a MySQL instance from a datadir we own. Idempotent: re-running while
# it's already up just reports that it's alive.
set -euo pipefail

MB=/opt/homebrew/opt/mysql/bin
DD=/opt/homebrew/var/mysql-cosmic
SOCK=/tmp/cosmic-mysql.sock

if "$MB/mysqladmin" --socket="$SOCK" ping 2>/dev/null | grep -q alive; then
  echo "MySQL already running (socket $SOCK)."
  exit 0
fi

if [ ! -d "$DD/mysql" ]; then
  echo "Initializing fresh datadir at $DD ..."
  "$MB/mysqld" --no-defaults --initialize-insecure --datadir="$DD"
fi

echo "Starting mysqld ..."
nohup "$MB/mysqld" --no-defaults \
  --datadir="$DD" \
  --socket="$SOCK" \
  --port=3306 \
  --bind-address=127.0.0.1 \
  --pid-file="$DD/cosmic.pid" \
  > /tmp/cosmic-mysql.log 2>&1 &

for i in $(seq 1 30); do
  if "$MB/mysqladmin" --socket="$SOCK" ping 2>/dev/null | grep -q alive; then
    echo "MySQL ready (try $i)."
    # Ensure root/1234 + cosmic db exist (no-op if already set).
    "$MB/mysql" --no-defaults --socket="$SOCK" -u root -p1234 -e \
      "CREATE DATABASE IF NOT EXISTS cosmic DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>/dev/null \
      || echo "(root password not yet set? run the one-time ALTER USER if this is a fresh datadir)"
    exit 0
  fi
  sleep 1
done

echo "ERROR: MySQL did not become ready. See /tmp/cosmic-mysql.log"
tail -15 /tmp/cosmic-mysql.log
exit 1
