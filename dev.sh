#!/usr/bin/env bash
# Единая точка входа для локальной разработки: поднимает Postgres+MailHog,
# backend и frontend одной командой. Останов — Ctrl+C, оба процесса упадут вместе.
set -e

cd "$(dirname "$0")"

echo "==> Postgres + MailHog (docker compose)"
docker compose up -d

cleanup() {
  echo ""
  echo "==> Останавливаю backend и frontend..."
  jobs -p | xargs -r kill 2>/dev/null
}
trap cleanup EXIT INT TERM

echo "==> Backend (./mvnw initialize spring-boot:run)"
(cd project-management-backend && ./mvnw initialize spring-boot:run) &

echo "==> Frontend (npm run dev)"
(cd project-management-react && npm run dev) &

wait
