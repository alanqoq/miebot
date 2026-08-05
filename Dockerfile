# syntax=docker/dockerfile:1.7

FROM debian:bookworm-slim AS dragonwell

ARG TARGETARCH
ARG DRAGONWELL_URL=https://github.com/dragonwell-project/dragonwell21/releases/download/dragonwell-extended-21.0.11.0.11%2B10_jdk-21.0.11-ga/Alibaba_Dragonwell_Extended_21.0.11.0.11.10_x64_linux.tar.gz
ARG DRAGONWELL_SHA256=12c642f8d6c6e0930b9b4e673d47822227ea46e7559c7b7b6b4c0331ace0580f

RUN test "$TARGETARCH" = "amd64" \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gzip tar \
    && rm -rf /var/lib/apt/lists/*

RUN curl --fail --location --retry 3 --output /tmp/dragonwell.tar.gz "${DRAGONWELL_URL}" \
    && echo "${DRAGONWELL_SHA256}  /tmp/dragonwell.tar.gz" | sha256sum -c - \
    && mkdir -p /opt/dragonwell \
    && tar -xzf /tmp/dragonwell.tar.gz \
        -C /opt/dragonwell \
        --strip-components=1 \
        --no-same-owner \
    && test -x /opt/dragonwell/bin/java \
    && /opt/dragonwell/bin/java -version \
    && rm /tmp/dragonwell.tar.gz

FROM node:24.15.0-bookworm-slim AS frontend-build

WORKDIR /workspace/qqbot-admin-web

COPY qqbot-admin-web/package.json qqbot-admin-web/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY qqbot-admin-web/ ./
RUN npm run build

FROM dragonwell AS backend-build

WORKDIR /workspace

COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ ./gradle/
COPY qqbot-domain/ ./qqbot-domain/
COPY qqbot-module-api/ ./qqbot-module-api/
COPY qqbot-module-spi/ ./qqbot-module-spi/
COPY qqbot-module-host/ ./qqbot-module-host/
COPY qqbot-protocol/ ./qqbot-protocol/
COPY qqbot-client/ ./qqbot-client/
COPY qqbot-gateway/ ./qqbot-gateway/
COPY qqbot-runtime/ ./qqbot-runtime/
COPY qqbot-plugin-api/ ./qqbot-plugin-api/
COPY qqbot-plugin-spi/ ./qqbot-plugin-spi/
COPY qqbot-plugin-host/ ./qqbot-plugin-host/
COPY qqbot-plugin-testkit/ ./qqbot-plugin-testkit/
COPY qqbot-plugin-example/ ./qqbot-plugin-example/
COPY qqbot-persistence/ ./qqbot-persistence/
COPY qqbot-admin-api/ ./qqbot-admin-api/
COPY qqbot-module-platform-admin/ ./qqbot-module-platform-admin/
COPY qqbot-module-database-support/ ./qqbot-module-database-support/
COPY qqbot-module-qqbot-runtime/ ./qqbot-module-qqbot-runtime/
COPY qqbot-module-plugin-support/ ./qqbot-module-plugin-support/
COPY qqbot-module-operations/ ./qqbot-module-operations/
COPY qqbot-module-cluster-support/ ./qqbot-module-cluster-support/
COPY qqbot-module-onebot11/ ./qqbot-module-onebot11/
COPY qqbot-app/ ./qqbot-app/
COPY --from=frontend-build /workspace/qqbot-admin-web/dist/ ./qqbot-admin-web/dist/

RUN /opt/dragonwell/bin/java \
        -classpath ./gradle/wrapper/gradle-wrapper.jar \
        org.gradle.wrapper.GradleWrapperMain \
        :qqbot-app:bootJar defaultModuleDirectory :qqbot-plugin-example:jar \
        --no-daemon \
    && cp ./qqbot-app/build/libs/qqbot-app-*.jar /tmp/qqbot-app.jar \
    && cp ./qqbot-plugin-example/build/libs/qqbot-plugin-example-*.jar /tmp/qqbot-plugin-example.jar \
    && mkdir -p /tmp/qqbot-modules \
    && cp ./build/runtime/modules/*.jar /tmp/qqbot-modules/

FROM debian:bookworm-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        fontconfig \
        fonts-noto-cjk \
        libfreetype6 \
        libstdc++6 \
        zlib1g \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 qqbot \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app qqbot \
    && mkdir -p /app /data/config /modules /plugins /tmp/qqbot \
    && chown -R 10001:10001 /app /data /modules /plugins /tmp/qqbot

COPY --from=dragonwell /opt/dragonwell /opt/dragonwell
COPY --from=backend-build --chown=10001:10001 /tmp/qqbot-app.jar /app/qqbot-app.jar
COPY --from=backend-build --chown=10001:10001 /tmp/qqbot-modules/ /modules/
COPY --from=backend-build --chown=10001:10001 /tmp/qqbot-plugin-example.jar /plugins/qqbot-plugin-example.jar

# Exercise the same headless font-metrics path used by image-rendering plugins.
RUN set -eu; \
    fail() { echo "Dockerfile: runtime AWT validation failed: $*" >&2; exit 1; }; \
    command -v fc-match >/dev/null 2>&1 || fail "fontconfig is missing (fc-match not found)"; \
    font_family="$(fc-match -f '%{family}\n' 'Noto Sans CJK SC' | sed -n '1p')"; \
    printf '%s\n' "$font_family" | grep -qi 'Noto Sans CJK' || fail "Noto CJK font is not selected (fc-match returned '$font_family')"; \
    printf '%s\n' \
        'import java.awt.image.BufferedImage;' \
        'final class AwtRuntimeCheck {' \
        '    public static void main(String[] args) {' \
        '        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);' \
        '        var graphics = image.createGraphics();' \
        '        try {' \
        '            graphics.getFontMetrics().stringWidth("MieBot font metrics");' \
        '        } finally {' \
        '            graphics.dispose();' \
        '        }' \
        '    }' \
        '}' > /tmp/AwtRuntimeCheck.java; \
    if ! /opt/dragonwell/bin/java -Djava.awt.headless=true /tmp/AwtRuntimeCheck.java; then \
        fail "headless font-metrics smoke test failed"; \
    fi; \
    rm -f /tmp/AwtRuntimeCheck.java

RUN mkdir -p /tmp/qqbot-jar /opt/sqlite \
    && cd /tmp/qqbot-jar \
    && /opt/dragonwell/bin/jar -xf /app/qqbot-app.jar \
    && sqlite_jar="$(find BOOT-INF/lib -maxdepth 1 -name 'sqlite-jdbc-*.jar' -print -quit)" \
    && test -n "$sqlite_jar" \
    && cd /opt/sqlite \
    && /opt/dragonwell/bin/jar -xf "/tmp/qqbot-jar/$sqlite_jar" \
        org/sqlite/native/Linux/x86_64/libsqlitejdbc.so \
    && mv org/sqlite/native/Linux/x86_64/libsqlitejdbc.so . \
    && chmod 0555 /opt/sqlite/libsqlitejdbc.so \
    && rm -rf /tmp/qqbot-jar /opt/sqlite/org

USER 10001:10001
WORKDIR /app

ENV JAVA_HOME=/opt/dragonwell \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp/qqbot -Dorg.sqlite.lib.path=/opt/sqlite -Djava.awt.headless=true -XX:MaxRAMPercentage=75.0" \
    QQBOT_HTTP_ADDRESS=0.0.0.0 \
    QQBOT_HTTP_PORT=8080 \
    QQBOT_MODULES_DIR=/modules \
    LOADER_PATH=/modules \
    QQBOT_PLUGINS_DATA_DIR=/data/plugin-data \
    QQBOT_ONBOARDING_STATE_FILE=/data/config/onboarding.json \
    QQBOT_GATEWAY_ENABLED=true \
    QQBOT_GATEWAY_SESSION_DIRECTORY=/data/config/gateway-sessions \
    QQBOT_GATEWAY_LEASE_DURATION=45s \
    QQBOT_ONEBOT11_CACHE_DIRECTORY=/data/onebot-cache \
    QQBOT_MEDIA_STAGING_DIRECTORY=/data/media-staging \
    QQBOT_DATABASE_CONFIG_FILE=/data/config/database.json \
    QQBOT_DATABASE_CANDIDATE_CONFIG_FILE=/data/config/database-candidate.json \
    QQBOT_SQLITE_PATH=/data/qqbot.db \
    QQBOT_MASTER_KEY_FILE=/data/config/app-secret.key

EXPOSE 8080
VOLUME ["/data", "/plugins"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/health/ready || exit 1

ENTRYPOINT ["/opt/dragonwell/bin/java", "-cp", "/app/qqbot-app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
