# AdaptCache Pro — single-image build (Item 8).
# Build:  docker build -f deploy/Dockerfile -t adaptcache-pro:latest .
# Run:    docker run -p 8080:8080 -p 9000:9000 adaptcache-pro:latest
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY src ./src
RUN javac --release 21 -d /out $(find src -name '*.java') \
    && echo "build ok" && ls /out/com/adaptivecache | head -3

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /out ./out
EXPOSE 8080 9000
ENV PROXY_PORT=8080 ORIGIN_PORT=9000
ENTRYPOINT ["java", "-cp", "out", "com.adaptivecache.Main", "server"]
