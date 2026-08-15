FROM maven:3.9.13-eclipse-temurin-21-noble AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests \
    -Dquarkus.package.jar.type=uber-jar \
    -Dquarkus.package.output-name=app \
    package

FROM eclipse-temurin:21-jre-noble

ARG APP_UID=10001
ARG APP_GID=10001

ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/opt/app/tmp -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl openssl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid "${APP_GID}" gatelink \
    && useradd --uid "${APP_UID}" --gid "${APP_GID}" --home-dir /opt/app --no-create-home --shell /usr/sbin/nologin gatelink \
    && mkdir -p /opt/app/config /opt/app/logs /opt/app/tmp /opt/app/tls \
    && chown -R "${APP_UID}:${APP_GID}" /opt/app

WORKDIR /opt/app

COPY --from=build --chown=${APP_UID}:${APP_GID} /build/target/app-runner.jar /opt/app/app.jar
COPY --chown=${APP_UID}:${APP_GID} docker-entrypoint.sh /opt/app/docker-entrypoint.sh

EXPOSE 8080 8443

USER ${APP_UID}:${APP_GID}

ENTRYPOINT ["sh", "/opt/app/docker-entrypoint.sh"]
