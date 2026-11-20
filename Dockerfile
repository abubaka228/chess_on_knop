FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY ChessServer.java .
RUN javac -encoding UTF-8 ChessServer.java
CMD ["java", "ChessServer"]
