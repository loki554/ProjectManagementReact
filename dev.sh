#!/usr/bin/env bash
# Единая точка входа для локальной разработки: поднимает Postgres+MailHog,
# backend и frontend одной командой. Останов — Ctrl+C, оба процесса упадут вместе.
set -e

cd "$(dirname "$0")"

# .env читается здесь и экспортируется в окружение — оттуда его берут и docker compose,
# и Spring (${DB_PASSWORD} в application-dev.yml). Раньше это делал properties-maven-plugin
# внутри pom.xml, из-за чего запускать бэкенд приходилось нестандартной командой
# "./mvnw initialize spring-boot:run", а обычная падала с "Circular placeholder reference"
# (8.4 IMPROVEMENTS.md). Теперь особенной команды нет: переменные приходят из окружения,
# как им и положено, а "./mvnw spring-boot:run" работает сам по себе.
#
# set -a включает автоэкспорт: всё, что присвоено между ним и set +a, уходит в окружение
# дочерних процессов. Точка (source), а не "export $(cat .env | xargs)": xargs ломается на
# значениях с пробелами и кавычками, а пароль от БД такое содержать вполне может.
if [ ! -f .env ]; then
  echo "Нет файла .env в корне репозитория." >&2
  echo "Скопируйте пример и подставьте свои значения: cp .env.example .env" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
. ./.env
set +a

echo "==> Postgres + MailHog (docker compose)"
docker compose up -d

cleanup() {
  echo ""
  echo "==> Останавливаю backend и frontend..."
  jobs -p | xargs -r kill 2>/dev/null
}
trap cleanup EXIT INT TERM

echo "==> Backend (./mvnw spring-boot:run)"
(cd project-management-backend && ./mvnw spring-boot:run) &

echo "==> Frontend (npm run dev)"
(cd project-management-react && npm run dev) &

wait
