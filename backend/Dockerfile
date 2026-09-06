# Multi-stage: the JDK and the Maven cache stay out of the shipped image.

# ------------------------------------------------------------------ build ---
# The widget is TypeScript and needs Node, which the JDK image does not have.
# Building it here keeps the jar self-contained: the API can then serve the
# exact script the dashboard's embed snippet points at.
FROM node:22-alpine AS widget
WORKDIR /widget
COPY widget/package.json widget/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY widget/tsconfig.json widget/build.mjs ./
COPY widget/src/ src/
RUN npm run build


FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Copied first and resolved on their own, so a source-only change reuses the
# cached dependency layer instead of re-downloading the world.
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY backend/src/ src/

# The built widget, picked up by the resources plugin. WORKDIR is /build, so
# the pom's ../widget/dist resolves to /widget/dist -- which is where this lands.
COPY --from=widget /widget/dist/ /widget/dist/
# Tests run in CI, not here: a build that needs a database container to produce
# an image is a build that fails for reasons unrelated to the image.
RUN ./mvnw -B clean package -DskipTests

# ----------------------------------------------------------------- runtime ---
FROM eclipse-temurin:21-jre-alpine AS runtime

# A non-root user, created explicitly. Containers run as root by default, which
# turns a container escape into a host compromise.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar app.jar

USER app
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes its heap from
# the container's cgroup limit, so changing the memory limit is enough and the
# two cannot disagree.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError"

# Reports readiness, not liveness: the container is only "healthy" once it can
# actually serve, which is what an orchestrator should wait for before routing.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

# Exec form via sh so JAVA_OPTS expands, and the JVM still receives signals as
# PID 1 - without exec, a SIGTERM would kill the shell and leave the JVM to be
# killed rather than shut down gracefully.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
