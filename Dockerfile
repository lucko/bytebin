# Start build with Alpine image
FROM alpine:3.12
# Install dependencies and prepare enviroment
RUN apk add --no-cache maven openjdk11 bash &&\
    addgroup -S bytebin &&\
    adduser -S bytebin -G bytebin &&\
    mkdir /buildTmp /opt/bytebin
# Copy source into container, build it and move everything to the right location
COPY docker/entrypoint.sh /entrypoint.sh
COPY ./ /buildTmp
RUN cd /buildTmp &&\
    mvn &&\
    cp ./target/bytebin.jar /opt/bytebin/bytebin.jar &&\
    chown -R bytebin:bytebin /opt/bytebin &&\
    cp docker/docker.config.json /opt/bytebin/docker.config.json &&\
    cd / &&\
    rm -rf /buildTmp
# Switch user for advanced docker security
USER bytebin
WORKDIR /opt/bytebin
# Set bytebin as entrypoint
ENTRYPOINT [ "/entrypoint.sh" ]