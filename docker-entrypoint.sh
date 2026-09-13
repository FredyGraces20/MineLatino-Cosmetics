#!/bin/sh
set -eu

# Railway volumes created by older images may be root-owned. Repair only the
# dedicated data mount, then permanently drop privileges before Node starts.
chown -R node:node /data
exec su-exec node "$@"
