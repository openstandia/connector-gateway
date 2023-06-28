#!/bin/bash

BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(cd "$BIN_DIR/.." && pwd)"

CLASSPATH=$BASE_DIR/lib/*

java -Xmx500m -Dlogback.configurationFile=$BASE_DIR/lib/logback.xml -classpath "$CLASSPATH" \
 jp.openstandia.connector.gateway.server.Main "$@"