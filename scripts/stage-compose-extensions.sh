#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
image=${QQBOT_IMAGE:-miebot:1.0.8}
container_id=

cleanup() {
    if [ -n "$container_id" ]; then
        docker rm "$container_id" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

mkdir -p "$project_root/modules" "$project_root/plugins"
container_id=$(docker create "$image")
docker cp "$container_id:/modules/." "$project_root/modules"
docker cp "$container_id:/plugins/." "$project_root/plugins"
