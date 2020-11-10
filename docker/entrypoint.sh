#!/bin/bash

if [ ! -f '/opt/bytebin/config.json' ]; then

    # Move file over to correct location
    mv /opt/bytebin/docker.config.json /opt/bytebin/config.json 

    # Verify variables
    if [ -z ${BYTEBIN_KEYLEN+x} ]; then
        BYTEBIN_KEYLEN=10
    fi

    if [ -z ${BYTEBIN_LIFETIME+x} ]; then
        BYTEBIN_LIFETIME=10080
    fi

    if [ -z ${BYTEBIN_CONTENTLEN+x} ]; then
        BYTEBIN_CONTENTLEN=10
    fi

    # write config file
    sed -i "s|#keyLength#|$BYTEBIN_KEYLEN|g" /opt/bytebin/config.json
    sed -i "s|#lifetimeMinutes#|$BYTEBIN_LIFETIME|g" /opt/bytebin/config.json
    sed -i "s|#maxContentLengthMb#|$BYTEBIN_CONTENTLEN|g" /opt/bytebin/config.json
fi

java -jar /opt/bytebin/bytebin.jar