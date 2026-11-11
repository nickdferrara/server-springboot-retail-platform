# syntax=docker/dockerfile:1

# Build from the repository root:
#   docker build -f docker/Dockerfile -t retail-platform .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY config config
COPY app app

RUN --mount=type=cache,target=/root/.gradle ./gradlew :app:bootJar --no-daemon
RUN java -Djarmode=tools -jar app/build/libs/retail-platform.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 retail \
    && useradd --system --uid 10001 --gid retail --no-create-home --shell /usr/sbin/nologin retail
WORKDIR /application

# Layers ordered from least to most frequently changed, so rebuilds reuse the cache.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "retail-platform.jar"]
