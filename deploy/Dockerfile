FROM eclipse-temurin:25-jre
WORKDIR /app
COPY market.jar /app/market.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/market.jar"]
