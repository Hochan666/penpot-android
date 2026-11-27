#!/usr/bin/env bash
# Penpot backend launcher (manual, no docker).
cd /opt/penpot/backend || exit 1

export JAVA_HOME=/opt/jre
export PATH=/opt/jre/bin:/opt/imagick/bin:$PATH
export LD_LIBRARY_PATH=/opt/imagick/lib/deps
export LANG=C.UTF-8
export TZ=Etc/UTC

# seccomp filter denies plain accept()/accept4(flags=0); route them through
# accept4(SOCK_CLOEXEC) via a freestanding shim (see penpot/shim/acceptshim.c)
export LD_PRELOAD=/opt/shim/libacceptshim.so

export JVM_OPTS="-Xmx1500m -Xms256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=384m -Djdk.lang.Process.launchMechanism=FORK"

export PENPOT_DATABASE_URI="postgresql://127.0.0.1:5432/penpot"
export PENPOT_DATABASE_USERNAME="penpot"
export PENPOT_DATABASE_PASSWORD="penpot"
export PENPOT_REDIS_URI="redis://127.0.0.1:6379/0"
export PENPOT_HTTP_SERVER_PORT="6060"
export PENPOT_HTTP_SERVER_HOST="0.0.0.0"
export PENPOT_PUBLIC_URI="http://192.168.2.8:3449"
export PENPOT_HOST="192.168.2.8"
export PENPOT_SECRET_KEY="b7f3c1a9d24e8065f3b19c7ad5e24081c3d7b6e59a0f482d15c7b3e9a6d0f2817"
export PENPOT_OBJECTS_STORAGE_BACKEND="fs"
export PENPOT_OBJECTS_STORAGE_FS_DIRECTORY="/opt/data/assets"
export PENPOT_FILE_DATA_BACKEND="db"
export PENPOT_TELEMETRY_ENABLED="false"
export PENPOT_FLAGS="disable-email-verification disable-admin-console"

exec ./run.sh
