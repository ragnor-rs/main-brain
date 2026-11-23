# Copies a prebuilt jar. Gradle runs on the developer's machine, never inside the image, so
# the production host needs no JDK and no Gradle — which is also why `docker compose build` must
# be preceded by `./gradlew shadowJar`.
FROM eclipse-temurin:21-jre

WORKDIR /app

# Debian rather than Alpine across all three JVM images. The ingest image will carry a native
# Telegram library, which is built against glibc; keeping the base consistent means that
# constraint does not later force one image onto a different distribution from the others.

COPY build/libs/backend.jar /app/backend.jar

# The model credential belongs to the backend — recognition and the answer path both need it.
# Nothing here declares it; it arrives from the environment at run time.
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
