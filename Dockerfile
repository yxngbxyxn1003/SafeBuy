FROM amazoncorretto:17-alpine
WORKDIR /app
COPY build/libs/SafeBuy-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]