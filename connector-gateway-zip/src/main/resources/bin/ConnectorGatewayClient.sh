#!/bin/bash

BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(cd "$BIN_DIR/.." && pwd)"

CLASSPATH=$BASE_DIR/lib/framework/*:$BASE_DIR/lib/gateway/*

cd $BASE_DIR && java -Xmx500m -Dlogback.configurationFile=$BASE_DIR/lib/logback.xml -classpath "$CLASSPATH" \
 jp.openstandia.connector.gateway.client.Main "$@"