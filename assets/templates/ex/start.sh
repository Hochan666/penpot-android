#!/usr/bin/env bash
# Penpot exporter launcher (manual, no docker).
cd /opt/penpot/exporter || exit 1

export PATH=/usr/bin:/opt/imagick/bin:$PATH
export LD_LIBRARY_PATH=/opt/imagick/lib/deps
export LANG=C.UTF-8
export TZ=Etc/UTC
export PLAYWRIGHT_BROWSERS_PATH=/opt/penpot/browsers
export HOME=/tmp

# seccomp: same accept() workaround as the backend
export LD_PRELOAD=/opt/shim/libacceptshim.so

# must match the backend's secret key -> exporter shared key is derived from it
export PENPOT_SECRET_KEY="b7f3c1a9d24e8065f3b19c7ad5e24081c3d7b6e59a0f482d15c7b3e9a6d0f2817"
# internal-uri must point at the FRONTEND (nginx): it serves render.html AND
# proxies /api to the backend. Pointing it at the backend breaks rasterization.
export PENPOT_INTERNAL_URI="http://127.0.0.1:3449"
export PENPOT_REDIS_URI="redis://127.0.0.1:6379/0"
export PENPOT_PUBLIC_URI="http://192.168.2.8:3449"
export PENPOT_HOST="192.168.2.8"
export PENPOT_HTTP_SERVER_PORT="6061"
export PENPOT_HTTP_SERVER_HOST="0.0.0.0"

exec node app.js
