# Образ фронтенда (8.1 IMPROVEMENTS.md): сборка Vite + раздача через nginx.
# Контекст сборки — каталог модуля; конфигурация nginx лежит в nginx/ (8.8).
#
# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- сборка
FROM node:24-alpine AS build
WORKDIR /build

# Сначала манифесты — слой с node_modules пересобирается при изменении зависимостей,
# а не при каждой правке компонента. npm ci, а не npm install: ставит ровно то, что
# записано в package-lock.json, и падает при расхождении с package.json. В образе нужна
# именно воспроизводимость, а не «свежие версии».
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Адрес API вшивается в бандл на этапе сборки — у Vite нет рантайм-конфигурации,
# import.meta.env подставляется в код при компиляции. Дефолт /api соответствует
# топологии из nginx/default.conf: тот же origin, проксирование на бэкенд, никакого CORS.
# Разнести по доменам можно через --build-arg, но тогда надо вернуть CORS на бэкенде
# и дописать origin в connect-src (см. nginx/security-headers.conf).
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---------------------------------------------------------------- рантайм
# nginx-unprivileged, а не обычный nginx: стандартный образ стартует от root и только
# воркеры роняет в пользователя nginx. Этот работает от uid 101 целиком и слушает 8080 —
# что совпадает с listen в нашем конфиге, потому что непривилегированный процесс не может
# занять порт ниже 1024. Наружу это всё равно 80/443 у прокси, который терминирует TLS.
FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime

# Конфиг раздачи и отдельный файл с заголовками безопасности (почему отдельный —
# см. комментарий в самом файле: правило наследования add_header в nginx).
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf

# Каталог точек расширения (8.9): в прод-образе он пуст, а на staging сюда монтируются
# noindex и basic-auth. Создаётся явно, чтобы include не смотрел в несуществующий путь.
#
# USER вокруг mkdir — не перестраховка: этот образ уже переключён на uid 101, и RUN в нём
# идёт от непривилегированного пользователя, которому /etc/nginx не принадлежит (сборка
# падала с exit code 1 ровно на этой строке). COPY выше проблемы не имеет: он кладёт
# файлы от root независимо от USER. Возвращаем 101 сразу же — рантайм обязан остаться
# непривилегированным, ради чего этот образ и выбран.
USER root
RUN mkdir -p /etc/nginx/snippets/environment
USER 101

COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 8080

# Проба про саму раздачу, а не про бэкенд: /healthz отдаёт сам nginx. Заворачивать её на
# API значило бы перезапускать контейнер фронта из-за недоступной БД.
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/healthz | grep -q ok || exit 1
