#!/bin/bash
# Run the concurrency stress test against a running server.
#
# Usage:
#   bash scripts/run-stress.sh [productId] [startStock] [threads]
#
# Defaults: productId=1, startStock=100, threads=10.
set -euo pipefail

cd "$(dirname "$0")/.."

CP="bin:lib/sqlite-jdbc-3.46.1.3.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar"

exec java -cp "$CP" com.warehouse.test.ConcurrencyStressTest "$@"
