# --- Stage 1: build the fat JAR --------------------------------------------
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /src
RUN apk add --no-cache curl unzip bash

# Install Gradle (pin to match gradle/wrapper/gradle-wrapper.properties)
ENV GRADLE_VERSION=8.14.3
RUN curl -fsSL https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -o /tmp/gradle.zip \
 && unzip -q /tmp/gradle.zip -d /opt \
 && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle \
 && rm /tmp/gradle.zip

# Cache dependencies separately from source for faster rebuilds.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN gradle --no-daemon shadowJar

# --- Stage 2: minimal runtime image ----------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /src/build/libs/phonetunnel-relay.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:${PORT:-8080}/health || exit 1
CMD ["sh", "-c", "java -Xms64m -Xmx256m -jar app.jar"]
