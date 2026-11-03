# bytebin Dockerfile
# Copyright (c) lucko - licenced MIT

# --------------
# BUILD PROJECT STAGE
# --------------
FROM maven:3-eclipse-temurin-25-alpine AS build-project

# compile the project
WORKDIR /bytebin
COPY pom.xml ./
COPY src/ ./src/
RUN mvn --no-transfer-progress -B package


# --------------
# RUNTIME BASE
# --------------
FROM eclipse-temurin:25-alpine AS runtime

RUN addgroup -S bytebin && adduser -S -G bytebin bytebin
USER bytebin

WORKDIR /opt/bytebin

# define a volume for the stored content
RUN mkdir content logs db
VOLUME ["/opt/bytebin/content", "/opt/bytebin/logs", "/opt/bytebin/db"]

# define a healthcheck
HEALTHCHECK --interval=1m --timeout=5s \
    CMD wget http://localhost:8080/health -q -O - | grep -c '{"status":"ok"}' || exit 1

# run the app
CMD ["java", "-jar", "bytebin.jar"]
EXPOSE 8080/tcp


# --------------
# PREBUILT TARGET
# --------------
FROM runtime AS prebuilt
COPY target/bytebin.jar .

# --------------
# DEFAULT TARGET
# --------------
FROM runtime
COPY --from=build-project /bytebin/target/bytebin.jar .
