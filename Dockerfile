# =========================
# Distribution artifact
# =========================
FROM scratch AS dist-artifact

COPY seatunnel-web-dist/target/seatunnel-web-*.tar.gz \
     /seatunnel-web.tar.gz


# =========================
# Backend runtime
# =========================
FROM eclipse-temurin:21-jre-jammy AS backend-runtime

ARG VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="SeaTunnel Web API" \
      org.opencontainers.image.description="SeaTunnel Web backend service" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

ENV SEATUNNEL_WEB_HOME=/opt/seatunnel-web \
    JAVA_OPTS="" \
    APP_OPTS="" \
    SERVER_PORT=9527 \
    SPRING_PROFILES_ACTIVE=mysql \
    SEATUNNEL_WEB_DATABASE_TYPE=mysql \
    SEATUNNEL_WEB_DATABASE_HOST=mysql \
    SEATUNNEL_WEB_DATABASE_PORT=3306 \
    SEATUNNEL_WEB_DATABASE_NAME=seatunnel_web \
    SPRING_DATASOURCE_URL="jdbc:mysql://mysql:3306/seatunnel_web?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true" \
    SPRING_DATASOURCE_USERNAME=seatunnel \
    SPRING_DATASOURCE_PASSWORD=seatunnel

WORKDIR ${SEATUNNEL_WEB_HOME}

COPY --from=dist-artifact \
     /seatunnel-web.tar.gz \
     /tmp/seatunnel-web.tar.gz

RUN set -eux; \
    mkdir -p "${SEATUNNEL_WEB_HOME}"; \
    tar -xzf /tmp/seatunnel-web.tar.gz \
        --strip-components=1 \
        -C "${SEATUNNEL_WEB_HOME}"; \
    rm -f /tmp/seatunnel-web.tar.gz; \
    chmod +x "${SEATUNNEL_WEB_HOME}"/bin/*.sh; \
    mkdir -p \
        "${SEATUNNEL_WEB_HOME}/logs" \
        "${SEATUNNEL_WEB_HOME}/jdbc-drivers"; \
    test -f "${SEATUNNEL_WEB_HOME}/libs/seatunnel-web-api.jar"

EXPOSE 9527

VOLUME ["/opt/seatunnel-web/logs","/opt/seatunnel-web/jdbc-drivers"]

ENTRYPOINT ["/opt/seatunnel-web/bin/run-seatunnel-web.sh"]


# =========================
# Native client backend (ARM64 only)
# =========================
FROM backend-runtime AS backend-native-arm64

ARG NATIVE_BUNDLE_SHA256=unknown
ARG BASE_IMAGE_ID=unknown

LABEL org.opencontainers.image.base.name="eclipse-temurin:21-jre-jammy" \
      org.seatunnel-web.native-client.bundle-checksums-sha256="${NATIVE_BUNDLE_SHA256}" \
      org.seatunnel-web.native-client.base-image-id="${BASE_IMAGE_ID}"

ENV SEATUNNEL_WEB_OFFLINE_IMPORT_DIMP_PATH=/opt/native-clients/bin/dimp \
    SEATUNNEL_WEB_OFFLINE_IMPORT_SYS_RESTORE_PATH=/opt/native-clients/bin/sys_restore \
    SEATUNNEL_WEB_OFFLINE_IMPORT_WORK_DIR=/opt/seatunnel-web/tmp/offline-import \
    SEATUNNEL_WEB_OFFLINE_IMPORT_PROCESS_TIMEOUT_MINUTES=240 \
    SEATUNNEL_WEB_OFFLINE_IMPORT_SQL_STATEMENT_TIMEOUT_SECONDS=300

COPY native-client-bundle/dm/ /opt/native-clients/dm/
COPY native-client-bundle/kingbase/ /opt/native-clients/kingbase/
COPY native-client-bundle/manifest.properties /opt/native-clients/manifest.properties
COPY native-client-bundle/SHA256SUMS /opt/native-clients/SHA256SUMS
COPY native-arm64/dimp /opt/native-clients/bin/dimp
COPY native-arm64/sys_restore /opt/native-clients/bin/sys_restore

RUN set -eux; \
    mkdir -p /opt/native-clients/bin /opt/seatunnel-web/tmp/offline-import; \
    chmod 0755 /opt/native-clients/bin/dimp /opt/native-clients/bin/sys_restore; \
    chmod 0775 /opt/seatunnel-web/tmp/offline-import; \
    dm_ld_path=/opt/native-clients/dm/bin:/opt/native-clients/dm/lib; \
    kingbase_ld_path=/opt/native-clients/kingbase/bin:/opt/native-clients/kingbase/lib; \
    ldd_output="$(LD_LIBRARY_PATH="${dm_ld_path}" ldd /opt/native-clients/dm/bin/dimp)"; \
    printf '%s\n' "${ldd_output}"; \
    ! printf '%s\n' "${ldd_output}" | grep -q 'not found'; \
    ldd_output="$(LD_LIBRARY_PATH="${kingbase_ld_path}" ldd /opt/native-clients/kingbase/bin/sys_restore)"; \
    printf '%s\n' "${ldd_output}"; \
    ! printf '%s\n' "${ldd_output}" | grep -q 'not found'; \
    /opt/native-clients/bin/sys_restore --version; \
    dimp_output="$(/opt/native-clients/bin/dimp HELP=Y 2>&1 || true)"; \
    printf '%s\n' "${dimp_output}" | grep -q 'USERID'; \
    test -x /opt/native-clients/bin/dimp; \
    test -x /opt/native-clients/bin/sys_restore; \
    test -w /opt/seatunnel-web/tmp/offline-import


# =========================
# Frontend runtime
# =========================
# Pinned tag: the health check below depends on curl, and "latest" could silently switch to a
# variant without it. The dependency is installed explicitly instead of relying on the base.
FROM nginx:1.27-bookworm AS frontend-runtime

ARG VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="SeaTunnel Web" \
      org.opencontainers.image.description="SeaTunnel Web frontend and reverse proxy" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

COPY --from=dist-artifact \
     /seatunnel-web.tar.gz \
     /tmp/seatunnel-web.tar.gz

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p /tmp/seatunnel-web; \
    tar -xzf /tmp/seatunnel-web.tar.gz \
        --strip-components=1 \
        -C /tmp/seatunnel-web; \
    test -f /tmp/seatunnel-web/web/index.html; \
    test -f /tmp/seatunnel-web/conf/nginx/default.conf; \
    rm -rf /usr/share/nginx/html/*; \
    cp -r /tmp/seatunnel-web/web/. /usr/share/nginx/html/; \
    chmod -R a+rX /usr/share/nginx/html; \
    cp /tmp/seatunnel-web/conf/nginx/default.conf \
       /etc/nginx/conf.d/default.conf; \
    rm -rf \
        /tmp/seatunnel-web \
        /tmp/seatunnel-web.tar.gz

EXPOSE 80

HEALTHCHECK \
    --interval=10s \
    --timeout=3s \
    --start-period=10s \
    --retries=6 \
    CMD curl -fsS -o /dev/null http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
