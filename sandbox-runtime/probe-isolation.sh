#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"

compose() {
  docker compose --file "$COMPOSE_FILE" "$@"
}

expect_failure() {
  label=$1
  shift
  if "$@" >/tmp/xiaoyu-probe.out 2>&1; then
    printf 'FAIL %s: operation unexpectedly succeeded\n' "$label"
    cat /tmp/xiaoyu-probe.out
    exit 1
  fi
  printf 'PASS %s\n' "$label"
}

command -v docker >/dev/null 2>&1 || {
  printf 'SKIP docker is not installed\n'
  exit 2
}

if [ -z "${SANDBOX_WORKSPACE_ROOT:-}" ]; then
  printf 'BLOCKED SANDBOX_WORKSPACE_ROOT is not set\n'
  exit 2
fi

compose config >/dev/null
compose up --build --detach browser-worker egress-proxy sandbox-redis
trap 'compose down --volumes --remove-orphans' EXIT INT TERM

container_id=$(compose ps --quiet browser-worker)
test -n "$container_id"

test "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$container_id")" = "true"
test "$(docker inspect --format '{{.HostConfig.PidsLimit}}' "$container_id")" = "256"
test "$(docker inspect --format '{{.HostConfig.Memory}}' "$container_id")" -gt 0
test "$(docker inspect --format '{{.HostConfig.NanoCpus}}' "$container_id")" -gt 0
test "$(compose exec --no-TTY browser-worker id -u)" = "1000"
printf 'PASS rootfs, cgroup, PID limit and non-root user\n'

expect_failure "read-only root filesystem" \
  compose exec --no-TTY browser-worker sh -c ': > /app/isolation-probe'

expect_failure "direct internet bypass" \
  compose exec --no-TTY browser-worker node -e \
  "fetch('https://example.com',{signal:AbortSignal.timeout(3000)}).then(()=>process.exit(0)).catch(()=>process.exit(7))"

set +e
compose run --rm --no-deps --entrypoint node browser-worker \
  -e "Buffer.alloc(3*1024*1024*1024,1)" >/tmp/xiaoyu-memory.out 2>&1
memory_status=$?
set -e
if [ "$memory_status" -eq 0 ]; then
  printf 'FAIL memory quota: 3 GiB allocation unexpectedly succeeded\n'
  exit 1
fi
printf 'PASS memory quota rejected 3 GiB allocation (status=%s)\n' "$memory_status"

expect_failure "fork bomb bounded by PID quota" \
  compose run --rm --no-deps --entrypoint timeout browser-worker 5 sh -c \
  'i=0; while [ "$i" -lt 2000 ]; do sleep 30 & i=$((i+1)); done'

expect_failure "infinite loop terminated by command timeout" \
  compose run --rm --no-deps --entrypoint timeout browser-worker 2 sh -c \
  'while :; do :; done'

compose exec --no-TTY browser-worker node -e \
  "fetch('http://127.0.0.1:8090/health').then(async r=>{const b=await r.json();if(!r.ok||!b.inContainer||!b.proxyConfigured)process.exit(1)})"
printf 'PASS Browser Worker health and isolation policy\n'

printf 'ALL CONTAINER ISOLATION PROBES PASSED\n'
