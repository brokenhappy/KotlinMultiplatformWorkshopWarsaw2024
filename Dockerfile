# Stage 1: Build the application using a Gradle image
FROM gradle:8.4-jdk17-alpine AS build
WORKDIR /home/gradle/src

# Copy only the necessary files to leverage Docker layer caching
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Copy the source code of all modules
COPY common ./common
COPY server ./server
COPY serverAndAdminCommon ./serverAndAdminCommon

# Build the server application and create a runnable distribution
# The --no-daemon flag is recommended for CI/CD environments
RUN ./gradlew :server:installDist --no-daemon

# Stage 2: Create the final, lightweight runtime image
FROM amazoncorretto:17-alpine-jdk
WORKDIR /app

# Copy the built application from the 'build' stage
COPY --from=build /home/gradle/src/server/build/install/server .

# The 'installDist' task creates a startup script in the 'bin' directory.
# This command will start the server.
ENTRYPOINT ["./bin/server"]