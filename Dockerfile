# syntax=docker/dockerfile:1.7
#
# DataBridge in one container: the Java server, with the built UI served from the same origin.
#
#   docker build --build-context ui=../DataBridge-FrontEnd/data-dashboard -t databridge .
#   docker run -p 8085:8085 -v databridge-data:/data databridge
#
# The "ui" build context (the DataBridge-FrontEnd/data-dashboard folder) is required.

# ---- UI: built from the DataBridge-FrontEnd repository (named build context "ui") ----
FROM node:20-alpine AS uibuild
WORKDIR /ui
COPY --from=ui package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY --from=ui . .
RUN node node_modules/vite/bin/vite.js build

# ---- Server: Maven build of the three modules, tests included ----
FROM maven:3.9-eclipse-temurin-17 AS server
WORKDIR /src
COPY pom.xml .
COPY databridge-core/pom.xml databridge-core/
COPY databridge-store-jdbc/pom.xml databridge-store-jdbc/
COPY databridge-app/pom.xml databridge-app/
RUN mvn -B -q dependency:go-offline
COPY . .
RUN mvn -B verify

# ---- Runtime: JRE only, unprivileged user, data on a volume ----
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --home-dir /data databridge && mkdir -p /data /app/ui && chown databridge /data
WORKDIR /app
COPY --from=server /src/databridge-app/target/databridge-app.jar /app/databridge-app.jar
COPY --from=uibuild /ui/dist /app/ui
USER databridge
ENV DATABRIDGE_HOST=0.0.0.0 \
    DATABRIDGE_PORT=8085 \
    DATABRIDGE_DB_URL="jdbc:h2:file:/data/databridge;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;AUTO_SERVER=TRUE" \
    DATABRIDGE_UI_DIR=/app/ui \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp -Duser.home=/data"
VOLUME /data
EXPOSE 8085
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8085 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '200' <&3"]
ENTRYPOINT ["java", "-jar", "/app/databridge-app.jar"]
