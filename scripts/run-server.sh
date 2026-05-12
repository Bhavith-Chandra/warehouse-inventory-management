#!/bin/bash
# Start the warehouse server. Default port 5555 and
# data/warehouse.db. Override with --port / --db.
set -euo pipefail

cd "$(dirname "$0")/.."

CP="bin:lib/sqlite-jdbc-3.46.1.3.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar"

mkdir -p data

exec java -cp "$CP" com.warehouse.server.InventoryServer "$@"
