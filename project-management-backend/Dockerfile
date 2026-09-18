# Образ бэкенда (8.1 IMPROVEMENTS.md). Контекст сборки — каталог модуля.
#
# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- сборка
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Сначала только то, из чего считается список зависимостей. Слой с ними пересобирается
# при изменении pom.xml, а не при каждой правке кода — то есть обычная пересборка не
# ходит в Maven Central вообще. Ради этого же порядка сюда едет и сам wrapper: версия
# Maven должна быть той же, что у разработчика, иначе «у меня собирается» перестаёт
# что-либо значить.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
# Тесты здесь не гоняются намеренно: они поднимают Testcontainers, то есть требуют докера
# внутри докера. Их место — в CI перед сборкой образа (8.7), а не в docker build, где
# единственным способом «починить» красный тест становится сборка с флагом.
RUN ./mvnw -B -ntp -DskipTests package

# Распаковка слоёного jar. Boot раскладывает содержимое по скорости изменения
# (dependencies → spring-boot-loader → snapshot-dependencies → application), и четыре
# COPY ниже превращаются в четыре слоя докера: правка нашего кода перекладывает
# последний, несколько мегабайт, а не весь fat jar со всеми зависимостями.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------- рантайм
# JRE, а не JDK: компилятор, jshell и отладочные утилиты в проде не нужны — это и лишний
# вес, и лишний инструментарий под рукой у того, кто получит шелл в контейнере.
#
# Про jlink, который предлагался в пункте: кастомный runtime-образ был бы меньше ещё
# примерно вдвое, но jdeps по fat jar Spring-приложения не даёт честного списка модулей
# (рефлексия, опциональные зависимости), так что на практике в jlink-образ всё равно
# включают модули с запасом — экономия выходит куда меньше обещанной, а обновление на
# следующий CVE в JDK перестаёт быть сменой одной строки FROM. Берём готовый JRE.
#
# Вариант alpine, а не ubuntu, по вполне приземлённой причине: в ubuntu-образе temurin нет
# ни curl, ни wget, то есть HEALTHCHECK ниже писать нечем — найдено смоук-тестом, где
# контейнер поднимался и работал, но вечно числился unhealthy с "wget: not found".
# Альтернативой было доставить curl через apt, то есть положить в прод-образ готовый
# инструмент выгрузки данных ради одной пробы. В alpine нужный wget уже есть в busybox.
# Приложение чисто джавовое (Tomcat, никаких нативных библиотек), так что musl вместо
# glibc для него разницы не делает.
FROM eclipse-temurin:25-jre-alpine AS runtime

# Непривилегированный пользователь. Фиксированные uid/gid, а не «какой дадут»: каталог
# хранилища вложений живёт в volume и переживает пересборку образа, а права на его файлы
# записаны числами — плавающий uid отобрал бы у приложения доступ к собственным файлам.
# addgroup/adduser, а не groupadd/useradd: busybox в alpine знает только их.
RUN addgroup -S -g 10001 app \
    && adduser -S -u 10001 -G app -h /app -s /sbin/nologin app

WORKDIR /app

# Каталог вложений и аватарок (STORAGE_BASE_PATH). Создаётся здесь, чтобы у точки
# монтирования были правильные владелец и права ещё до того, как docker навесит volume:
# иначе каталог создаст демон от root, и приложение не сможет в него писать.
RUN mkdir -p /app/storage && chown -R app:app /app

COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

USER app

EXPOSE 8080

# Профиль не зашит: SPRING_PROFILES_ACTIVE приходит из compose/оркестратора. Без него
# приложение не поднимется вообще (нет датасорса, почты и JWT-секрета) — и это желаемое
# поведение, см. комментарий в application.properties.

# MaxRAMPercentage вместо -Xmx: лимит памяти задаёт оркестратор, и JVM должна считать свою
# кучу от него, а не от памяти хоста. 75% — оставляем четверть на метаспейс, стеки потоков
# и прямые буферы, без которых контейнер убивал бы OOM-killer при формально живой куче.
#
# JAVA_TOOL_OPTIONS, а не собственная переменная в ENTRYPOINT-обёртке: её JVM читает сама,
# и это позволяет оставить ENTRYPOINT в exec-форме. Через "sh -c" (чтобы развернуть свою
# переменную) PID 1 становится шелл, сигналы до джавы доходят через него, а аргументы
# docker run уезжают в $0/$1 шелла вместо приложения — то есть "docker run образ id -u"
# запускает не id, а всё тот же java. На это уже наступили, когда писали проверку
# «контейнер не от root» в CI.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# Проба контейнера — readiness, а не liveness: docker'у важно «можно ли слать трафик»
# (именно на это смотрит depends_on: service_healthy у фронтенда), и БД в эту группу
# входит. См. 8.6. wget здесь busybox'ный — он есть в alpine из коробки.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

# JarLauncher из распакованного слоёного layout'а, а не java -jar: fat jar здесь уже не
# лежит, его содержимое разложено по каталогам выше.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
