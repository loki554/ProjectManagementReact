# PM Tracker

Веб-трекер задач в духе Taiga / Redmine: проекты с ролями участников, задачи и подзадачи,
канбан-доска, спринты, учёт времени, вложения, вики и уведомления. Монорепо:
бэкенд на Spring Boot, SPA на React.

## Возможности

- **Проекты и доступ** — роли `OWNER` / `ADMIN` / `MEMBER` / `VIEWER` (права проверяются
  на бэкенде), приглашения по email-ссылке, избранное, архивирование.
- **Задачи** — подзадачи, статусы (новая → в работе → на паузе → feedback → выполнена /
  отклонена), исполнитель, срочность, дедлайн, теги, категории, чек-листы, зависимости
  («блокирует / заблокирована»), шаблоны задач, массовые действия, корзина с восстановлением.
- **Канбан и спринты** — drag & drop между колонками, планирование спринтов.
- **Совместная работа** — комментарии с `@упоминаниями`, Markdown в описаниях, вики проекта,
  лента активности, живые обновления через SSE (изменения видны в соседней вкладке сразу).
- **Учёт времени** — списание часов на задачи, отчёт по времени, дашборд проекта.
- **Поиск и представления** — полнотекстовый поиск (PostgreSQL FTS), сохранённые фильтры.
- **Файлы** — вложения до 20 МБ с превью, аватарки; хранение на диске или в S3/MinIO.
- **Уведомления** — в приложении и по почте, ежедневная сводка, настройки на уровне
  пользователя и проекта, отписка по ссылке из письма.
- **Экспорт** — задачи в CSV/JSON, время в CSV, вики в Markdown.
- **Аккаунт** — регистрация с подтверждением email, сброс пароля, JWT access + refresh
  с ротацией, rate limiting на входе и регистрации.
- **Интерфейс** — русский, английский, немецкий; светлая и тёмная тема.

## Стек

| | |
|---|---|
| Бэкенд | Java 25, Spring Boot 4.1 (Web MVC, Security, Data JPA, Mail), Flyway, JJWT, Bucket4j, Apache Tika, springdoc-openapi |
| База | PostgreSQL 16 |
| Фронтенд | React 19, Vite 8, Tailwind CSS 4, TanStack Query, Zustand, React Router 7, React Hook Form + Zod, dnd-kit, i18next |
| Тесты | JUnit 5 + Testcontainers + GreenMail, JaCoCo; Vitest + Testing Library; Playwright (E2E) |
| Инфраструктура | Docker Compose, nginx (раздача SPA + прокси API), GitHub Actions, Dependabot |

## Структура

```
.
├── project-management-backend/   Spring Boot API (миграции — src/main/resources/db/migration)
├── project-management-react/     React SPA, nginx-конфиг, E2E-тесты (e2e/)
├── ops/backup/                   скрипты бэкапа и проверки восстановлением
├── ops/staging/                  nginx-накладки стенда (noindex, basic-auth)
├── docker-compose.yml            dev: Postgres, MailHog, MinIO
├── docker-compose.prod.yml       прод-стек
├── docker-compose.staging.yml    стенд — накладка на прод-стек
└── dev.sh                        запуск всего dev-окружения одной командой
```

## Локальный запуск

**Нужно:** JDK 25, Node.js 24, Docker. На Windows `dev.sh` запускается из Git Bash.

```bash
cp .env.example .env    # пароль для локальной базы
./dev.sh                # Postgres + MailHog в Docker, бэкенд и фронтенд; Ctrl+C — остановить всё
```

| Что | Адрес |
|---|---|
| Приложение | http://localhost:5173 |
| API | http://localhost:8080/api |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| MailHog (письма подтверждения и сброса) | http://localhost:8025 |

Схема базы создаётся Flyway при старте бэкенда. Файлы по умолчанию пишутся в
`project-management-backend/storage/`; чтобы проверить S3-ветку, поднимите MinIO из того же
`docker-compose.yml` и переключите `app.storage.type=s3` в `application-dev.yml`.

По отдельности, без `dev.sh`:

```bash
docker compose up -d
cd project-management-backend && ./mvnw spring-boot:run   # переменные из .env должны быть в окружении
cd project-management-react && npm install && npm run dev
```

## Тесты

```bash
# бэкенд: юнит- и интеграционные тесты на Testcontainers (нужен запущенный Docker) + пороги покрытия JaCoCo
cd project-management-backend && ./mvnw verify

# фронтенд
cd project-management-react
npm run lint
npm test          # Vitest
npm run e2e       # Playwright против поднятого ./dev.sh; первый раз: npx playwright install chromium
```

E2E-сценарии регистрируют новых пользователей и упираются в лимит регистраций: повторный
прогон в течение часа получит `429` — перезапустите бэкенд.

## Деплой

Прод — это `docker-compose.prod.yml`: Postgres, бэкенд, nginx с собранным фронтендом и
сервис бэкапов. Наружу публикуется один порт nginx (`HTTP_PORT`, по умолчанию 8080); база и
бэкенд доступны только внутри сети compose, `/api/` проксируется на тот же origin.

### 1. Конфигурация

```bash
cp .env.prod.example .env.prod
```

Обязательные переменные (подробности — в комментариях `.env.prod.example`):

| Переменная | Что это |
|---|---|
| `DB_PASSWORD` | пароль базы; действует только при первой инициализации тома, потом меняется через `ALTER USER` |
| `JWT_SECRET` | ≥ 32 байт случайных данных: `openssl rand -base64 48` |
| `PUBLIC_BASE_URL` | адрес, который видит пользователь (`https://tasks.example.com`), — идёт в ссылки писем и CORS |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | SMTP-релей со STARTTLS (порт 587); без почты не завершить регистрацию |
| `TZ` | часовой пояс — по нему считаются сводка уведомлений (9:00) и бэкапы |

Незаданная переменная роняет бэкенд на старте — это сделано намеренно. Для хранения файлов в
S3 добавьте `STORAGE_TYPE=s3` и `S3_*` (см. `application-prod.yml`); это же нужно, если
бэкендов будет больше одного.

### 2. Запуск

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

Бэкенд ждёт готовности базы, nginx — готовности бэкенда (`/actuator/health/readiness`),
миграции Flyway применяются автоматически.

### 3. HTTPS

TLS в стек не входит: перед ним нужен прокси с ACME — Caddy, Traefik или облачный
балансировщик. Он должен передавать `X-Forwarded-Proto` и не буферизовать
`/api/realtime/stream` (SSE). Минимальный пример для Caddy:

```
tasks.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Порт `HTTP_PORT` в интернет не открывайте. Учтите, что Docker публикует порты в обход `ufw`,
поэтому закрывайте его на уровне облачного firewall или прокси.

### 4. Обновление

```bash
git pull
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

Новые миграции применятся при старте бэкенда. Откатиться на код старше схемы базы нельзя:
в этом случае восстанавливайте базу из бэкапа.

### Бэкапы

Сервис `backup` каждый день в `BACKUP_AT` снимает `pg_dump`, проверяет дамп восстановлением
во временную базу и хранит `BACKUP_RETENTION_DAYS` дней. Дампы лежат в томе на том же хосте,
поэтому их нужно выгружать наружу (restic / rclone / `aws s3 sync`). Вложения в дамп не
входят: том `storage` бэкапится отдельно. Ручной дамп и порядок восстановления описаны в
[ops/backup/README.md](ops/backup/README.md).

### Стенд

Стенд — это накладка на прод-файл, а не отдельная конфигурация. Он собирает те же образы,
но почта уходит в MailHog, все ответы получают `noindex`, весь стенд закрыт basic-auth,
rate limiting выключен, Swagger включён.

```bash
cp .env.staging.example .env.staging
docker run --rm httpd:2-alpine htpasswd -nbB staging '<пароль>' > ops/staging/htpasswd
docker compose --env-file .env.staging \
  -f docker-compose.prod.yml -f docker-compose.staging.yml up -d --build
```

Чек-лист проверки перед выкатом — в [ops/staging/README.md](ops/staging/README.md).

## CI

- **`ci.yml`** (каждый PR и push в `main`) — `./mvnw verify` с порогами покрытия; lint, тесты
  и сборка фронтенда; сборка обоих Docker-образов с проверкой, что контейнеры не запускаются
  от root.
- **`security.yml`** (еженедельно и при изменении зависимостей) — `npm audit` и OWASP
  Dependency-Check (только если в секретах есть `NVD_API_KEY`).
- **Dependabot** — npm, Maven, Docker-образы и GitHub Actions.
