#!/bin/sh
# Restarts the mock API in the background (pid in out/mock.pid).
cd "$(dirname "$0")"
[ -f out/mock.pid ] && kill "$(cat out/mock.pid)" 2>/dev/null
node mock/server.mjs > out/mock.log 2>&1 &
echo $! > out/mock.pid
sleep 1
