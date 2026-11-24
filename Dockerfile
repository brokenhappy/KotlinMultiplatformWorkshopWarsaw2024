# Stage 1: Build the application using a Gradle image
FROM eclipse-temurin:25-jdk AS build
WORKDIR /home/gradle/src

RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

# Gradle configures every included project before building the server.
COPY . .

RUN ./gradlew :server:installDist --no-daemon

# Stage 2: Create the final, lightweight runtime image
FROM amazoncorretto:25
RUN dnf install -y findutils && dnf clean all
WORKDIR /app

# Copy the built application from the 'build' stage
COPY --from=build /home/gradle/src/server/build/install/server .

# The 'installDist' task creates a startup script in the 'bin' directory.
# This command will start the server.
ENTRYPOINT ["./bin/server"]
