# Build context is the repository root so the framework-free engine module is available.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY engine ./engine
COPY backend ./backend
COPY ml-service/app/features.py ./ml-service/app/features.py
RUN mvn -q -DskipTests -pl backend -am package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/backend/target/reconcile-backend-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
