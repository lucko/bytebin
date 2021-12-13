# bytebin Dockerfile
# Copyright (c) lucko - licenced MIT

# --------------
# BUILD STAGE
# --------------
FROM eclipse-temurin:17-alpine as build

# for objcopy, needed by jlink
RUN apk add binutils

# create a minimal JRE
#ENV JAVA_TOOL_OPTIONS="-Djdk.lang.Process.launchMechanism=vfork"
RUN $JAVA_HOME/bin/jlink \
    --add-modules java.base,java.logging,java.xml,java.desktop,java.management,java.sql,java.naming,jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /jre

# install maven
RUN apk add maven

# compile the project
WORKDIR /bytebin
COPY pom.xml ./
COPY src/ ./src/
RUN mvn -B package


# --------------
# RUN STAGE
# --------------
FROM alpine

# copy JRE from build stage
ENV JAVA_HOME=/opt/java
ENV PATH "${JAVA_HOME}/bin:${PATH}"
COPY --from=build /jre $JAVA_HOME

RUN addgroup -S bytebin && adduser -S -G bytebin bytebin
USER bytebin

# copy app from build stage
WORKDIR /opt/bytebin
COPY --from=build /bytebin/target/bytebin.jar .

# define a volume for the stored content
RUN mkdir content && chown bytebin:bytebin content/
VOLUME ["/opt/bytebin/content"]

# define a healthcheck
HEALTHCHECK --interval=1m --timeout=5s \
    CMD wget http://localhost:8080/health -q -O - | grep -c '{"status":"ok"}' || exit 1

# run the app
CMD ["java", "-jar", "bytebin.jar"]
EXPOSE 8080/tcp
