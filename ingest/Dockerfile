# Copies a prebuilt jar. See backend/Dockerfile for why the build happens outside the image.
FROM eclipse-temurin:21-jre

WORKDIR /app

# This is the image that carries TDLib, and the only one — the backend has no reason to hold a
# Telegram client, and keeping it off that classpath is why source adapters live in `:ingest`
# rather than in `:data`.
#
# There is no apt-get for it, which is worth stating because its absence looks like an
# oversight. The compiled library travels inside the jar, at
# `META-INF/tdlightjni/libtdjni.linux_<arch>_gnu_ssl3.so`, for amd64 and arm64, and the wrapper
# extracts the one it needs on first use; what it then links against — glibc, OpenSSL 3, zlib,
# libstdc++ — this base image already provides. That is also what ties the artifact to the base:
# the classifier names an architecture, a C library and an OpenSSL major, and changing the `FROM`
# line to a musl or OpenSSL 1 image would leave the native unable to load with nothing in the
# build to say so.
#
# The jar is correspondingly large, around 70 MB against the other two services' few. That is
# the two native libraries' size and not a packaging fault.

COPY build/libs/ingest.jar /app/ingest.jar

# This container holds live session credentials and must never hold the model key: capture
# needs no model access, and the two together would put the key beside a logged-in account.
ENTRYPOINT ["java", "-jar", "/app/ingest.jar"]
