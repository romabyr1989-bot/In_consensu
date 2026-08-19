# syntax=docker/dockerfile:1
#
# Multi-stage image (§4). The runtime is a plain OpenJDK 21 JRE, so the same image definition
# works with Axiom JDK / Liberica base images inside the customer perimeter (NFR-4).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY config/ config/
COPY src/ src/

# Tests, formatting and coverage run in CI (./mvnw verify); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true -Djacoco.skip=true package \
    && cp target/cus.jar /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system cus \
    && useradd --system --gid cus --create-home --home-dir /app cus

WORKDIR /app
COPY --from=build --chown=cus:cus /workspace/app.jar /app/app.jar

USER cus
EXPOSE 8080

# §8.7: the JVM works in UTC, business dates are converted using cus.timezone.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=UTC -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
