# bytebin Dockerfile
# Copyright (c) lucko - licenced MIT

# --------------
# BUILD STAGE
# --------------
FROM eclipse-temurin:17-alpine as build

# for objcopy, needed by jlink
RUN apk add binutils

# create a minimal JRE
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
COPY . .
RUN mvn -B clean package


# --------------
# RUN STAGE
# --------------
FROM alpine

# copy JRE from build stage
ENV JAVA_HOME=/opt/java
ENV PATH "${JAVA_HOME}/bin:${PATH}"
COPY --from=build /jre $JAVA_HOME

# copy app from build stage
WORKDIR /opt/bytebin
COPY --from=build /bytebin/target/bytebin.jar .

# define a healthcheck
HEALTHCHECK --interval=1m --timeout=5s \
    CMD wget http://localhost:8080/health -q -O - | grep -c '{"status":"ok"}' || exit 1

# run the app
CMD ["java", "-jar", "bytebin.jar"]
EXPOSE 8080/tcp
