#!/bin/sh
# 家庭事项记录 · 数据冷备（零依赖，任何 Linux / NAS 上都能跑）
# 用法：sh scripts/backup.sh
# 定时任务：30 2 * * * /bin/sh /路径/grades-track/scripts/backup.sh >> /路径/grades-track/backups/backup.log 2>&1
# 优先用容器内的 root 打包（避开 NAS 上 root 所有的附件目录权限问题），无 Docker 时回退宿主机 tar。
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BK="$DIR/backups"
mkdir -p "$BK"
TS=$(date +%Y%m%d-%H%M%S)
OUT="$BK/family-hub-$TS.tar.gz"
TMP="$BK/.tmp-backup.tar.gz"
rm -f "$TMP"

USE_DOCKER=0
if command -v docker >/dev/null 2>&1; then
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'grades-track'; then USE_DOCKER=1; fi
fi

if [ "$USE_DOCKER" = 1 ]; then
  docker exec grades-track sh -c 'cd /app || exit 1
    set -- data/grades.json
    for f in data/auth.json data/attachments.json public/daily-ledger.json public/avatar.jpg; do
      [ -f "$f" ] && set -- "$@" "$f"
    done
    [ -d data/attachments ] && set -- "$@" data/attachments
    tar -czf /app/backups/.tmp-backup.tar.gz "$@"' || { echo '容器内打包失败' >&2; exit 1; }
  mv -f "$TMP" "$OUT"
else
  chmod -R u+rwX,go+rX "$DIR/data" "$DIR/public" 2>/dev/null || true
  set -- data/grades.json
  for f in data/auth.json data/attachments.json public/daily-ledger.json public/avatar.jpg; do
    [ -f "$DIR/$f" ] && set -- "$@" "$f"
  done
  [ -d "$DIR/data/attachments" ] && set -- "$@" data/attachments
  tar -czf "$OUT" -C "$DIR" "$@"
fi

# 清单与保留策略：只留最近 30 份，避免备份本身占满磁盘
tar -tzf "$OUT" > "${OUT%.tar.gz}.list.txt" 2>/dev/null || true
# shellcheck disable=SC2012
ls -1t "$BK"/family-hub-*.tar.gz 2>/dev/null | tail -n +31 | while IFS= read -r old; do
  rm -f "$old" "${old%.tar.gz}.list.txt"
done
echo "$OUT"
