#!/bin/sh
# 从 backup.sh 生成的包恢复数据（会覆盖当前数据，请先再备份一次）
# 用法：sh scripts/restore.sh backups/family-hub-20261105-023000.tar.gz
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -z "${1:-}" ] || [ ! -f "$1" ]; then
  echo "用法: sh scripts/restore.sh <备份包 .tar.gz>"
  echo "可用备份："
  ls -1t "$DIR"/backups/family-hub-*.tar.gz 2>/dev/null || true
  exit 1
fi
echo "即将用 [$1] 覆盖：$DIR/data/ 与 public/daily-ledger.json、public/avatar.jpg"
printf '确认请输入 yes: '
read -r ans
if [ "$ans" != "yes" ]; then echo "已取消，未改动任何文件"; exit 1; fi

USE_DOCKER=0
if command -v docker >/dev/null 2>&1; then
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'grades-track'; then USE_DOCKER=1; fi
fi
if [ "$USE_DOCKER" = 1 ]; then
  ABS=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd)/$(basename "$1")
  docker cp "$ABS" grades-track:/app/backups/.restore.tar.gz
  docker exec grades-track sh -c 'cd /app && tar -xzf /app/backups/.restore.tar.gz && rm -f /app/backups/.restore.tar.gz'
  docker exec grades-track chmod -R a+rX /app/data /app/public 2>/dev/null || true
else
  tar -xzf "$1" -C "$DIR"
fi
echo "恢复完成。请重启容器使数据生效：docker restart grades-track"
