#!/usr/bin/env bash
# Launch the Cosmic server locally for the M1 milestone smoke test.
#
# Prereqs (set up 2026-05-30):
#   - JDK 21 at /opt/homebrew/opt/openjdk@21
#   - MySQL 9.6 running as a self-owned instance (see start-mysql.sh) with
#     root/1234 and a `cosmic` database, reachable on 127.0.0.1:3306
#   - Project built: ./mvnw clean install -DskipTests  (produces the shaded
#     fat jar target/Cosmic.jar with all deps bundled)
#   - MapleStory v83 WZ data extracted into ./wz  (or pass WZPATH=/path/to/wz)
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -d wz ] && [ -z "${WZPATH:-}" ]; then
  echo "ERROR: no ./wz directory found. Extract MapleStory v83 WZ data into ./wz,"
  echo "       or run: WZPATH=/path/to/wz ./run-server.sh"
  exit 1
fi

if [ ! -f target/Cosmic.jar ]; then
  echo "ERROR: target/Cosmic.jar not found. Build first: ./mvnw clean install -DskipTests"
  exit 1
fi

# WZFiles reads the "wz-path" system property; default is ./wz when unset.
WZ_ARG="${WZPATH:+-Dwz-path=$WZPATH}"

exec java -Xmx4g $WZ_ARG -cp target/Cosmic.jar net.server.Server
