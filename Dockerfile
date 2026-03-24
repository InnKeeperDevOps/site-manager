FROM gradle:8.14-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app

# Install Node.js (required by Claude CLI) and git
RUN apt-get update && \
    apt-get install -y curl git && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Claude CLI globally
RUN npm install -g @anthropic-ai/claude-code

COPY --from=build /app/build/libs/*.jar app.jar

# Create workspace directory for cloning repos
RUN mkdir -p /workspace

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
