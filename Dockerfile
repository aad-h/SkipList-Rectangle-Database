# SkipDB: Redis-compatible sorted-set database on a ranked skip list.
#
#   docker build -t skipdb .
#   docker run -d --name skipdb -p 6380:6380 -v skipdb-data:/data skipdb
#   redis-cli -p 6380 ZADD board 100 alice
#
# The build stage compiles with plain javac (no Maven, no dependencies), so
# the image builds without network access beyond the two base images.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY src/main/java ./java
RUN mkdir classes \
 && javac --release 11 -encoding UTF-8 -d classes $(find java -name '*.java') \
 && jar --create --file /skipdb.jar --main-class skipdb.SkipDBServer -C classes .

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --home-dir /data skipdb \
 && mkdir /data && chown skipdb /data
COPY --from=build /skipdb.jar /app/skipdb.jar
USER skipdb
WORKDIR /data
VOLUME /data
# 6380: RESP (redis-cli, client libraries); 8080: leaderboard demo (--demo)
EXPOSE 6380 8080
# PING over RESP with bash's /dev/tcp, so the image needs no redis-cli
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/6380 && printf "PING\r\n" >&3 && timeout 2 head -c 7 <&3 | grep -q PONG'
# Container-aware heap sizing; docker stop's SIGTERM runs SkipDB's shutdown
# hook, which flushes and fsyncs the append-only file.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/skipdb.jar"]
# Redis's event loop, with persistence into the /data volume. Override to
# change flags, e.g. `docker run ... skipdb 6380 --io nio --demo`.
CMD ["6380", "--io", "nio", "--aof", "/data/appendonly.aof", "--appendfsync", "everysec"]
