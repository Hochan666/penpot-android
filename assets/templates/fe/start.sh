#!/usr/bin/env bash
# Penpot frontend (nginx) launcher — manual, no docker.
set -e

export PENPOT_BACKEND_URI="http://127.0.0.1:6060"
export PENPOT_EXPORTER_URI="http://127.0.0.1:6061"
export PENPOT_PUBLIC_URI="http://192.168.2.8:3449"
export PENPOT_FLAGS="disable-email-verification disable-admin-console"
export PENPOT_HTTP_SERVER_WORKER_PROCESSES="2"
export PENPOT_HTTP_SERVER_WORKER_CONNECTIONS="1024"
export PENPOT_INTERNAL_RESOLVER="192.168.2.1 223.5.5.5"
export PENPOT_DISABLE_IPV6_LISTEN="true"

# seccomp filter denies plain accept(); route through accept4(SOCK_CLOEXEC)
export LD_PRELOAD=/opt/shim/libacceptshim.so

exec /entrypoint.sh nginx -g "daemon off;"
