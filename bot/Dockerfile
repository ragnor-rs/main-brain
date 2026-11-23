# Copies a prebuilt jar. See backend/Dockerfile for why the build happens outside the image.
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY build/libs/bot.jar /app/bot.jar

# The bot holds a Bot API token and nothing else: no database credential, no model key. It
# reaches everything through the backend.
ENTRYPOINT ["java", "-jar", "/app/bot.jar"]
