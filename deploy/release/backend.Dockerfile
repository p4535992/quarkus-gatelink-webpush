FROM eclipse-temurin:21-jre-noble

ARG APP_UID=10001
ARG APP_GID=10001

ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/opt/app/tmp -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid "${APP_GID}" gatelink \
    && useradd --uid "${APP_UID}" --gid "${APP_GID}" --home-dir /opt/app --no-create-home --shell /usr/sbin/nologin gatelink \
    && mkdir -p /opt/app/config /opt/app/logs /opt/app/tmp \
    && chown -R "${APP_UID}:${APP_GID}" /opt/app

WORKDIR /opt/app

COPY --chown=${APP_UID}:${APP_GID} app.jar /opt/app/app.jar

EXPOSE 8080

USER ${APP_UID}:${APP_GID}

ENTRYPOINT ["java", "-jar", "/opt/app/app.jar"]
