# SkipDB Roadmap

Growing the CS 3114 skip list into **SkipDB**: a Redis-compatible in-memory
database whose sorted sets run on a custom ranked skip list (the same structure
Redis uses for `ZSET`).

Target resume line:

> Built a Redis-compatible sorted-set database in Java on a custom ranked skip
> list; speaks RESP so `redis-cli` and standard clients work; N ops/sec with
> append-only-file persistence.

## Phase 1: Core + protocol
- [x] `RankedSkipList`: span-augmented skip list (O(log n) rank / select / range)
- [x] `SortedSet`: hash map + skip list, like Redis's `zset`
- [x] RESP2 parser/encoder (arrays of bulk strings + inline commands)
- [x] Commands: PING ECHO SET GET DEL EXISTS TYPE KEYS DBSIZE FLUSHALL,
      ZADD ZINCRBY ZSCORE ZCARD ZREM ZRANK ZREVRANK ZRANGE ZREVRANGE
      ZRANGEBYSCORE ZCOUNT
- [x] Multi-client TCP server (`redis-cli -p 6380` works)
- [x] Client handshake (HELLO, CLIENT, SELECT, INFO) and MULTI/EXEC/DISCARD,
      verified with redis-cli, redis-benchmark and redis-py
- [x] Randomized tests against `TreeSet`, protocol tests, socket integration test

## Phase 2: Durability, expiry & RESP3
- [ ] RESP3 (`HELLO 3`): redis-py 8 defaults to it; needs map/double/null types
- [x] Append-only file (AOF) with `everysec` / `always` fsync, replay on startup
- [x] AOF rewrite/compaction
- [ ] `EXPIRE` / `TTL` with lazy + periodic eviction
- [ ] `ZADD` flags: NX, XX, GT, LT, CH, INCR

## Phase 3: Performance
- [ ] JMH benchmarks: RankedSkipList vs `TreeMap` vs `ConcurrentSkipListMap`
- [ ] End-to-end benchmark with `redis-benchmark` vs real Redis on the same box
- [ ] Single-threaded NIO event loop (Redis model) vs thread-per-client: compare
      throughput and p99 latency
- [ ] Results table + charts in README

## Phase 4: Showcase
- [ ] Live leaderboard demo (small web page fed by SkipDB)
- [ ] Docker image, `docker run -p 6380:6380 skipdb`
- [ ] Architecture diagram and a write-up of the design trade-offs

---

## Scheduled backlog (worked top to bottom, 1–2 commits per session)

Each item must ship with tests, keep `OK` from the full suite, and update the
README if user-visible. Tick the box in the same commit that finishes it.

- [x] 1. AOF persistence: append every write command to `appendonly.aof`, replay it on startup (`--aof <path>` flag)
- [x] 2. AOF fsync policy (`always` / `everysec` / `no`) + `BGREWRITEAOF` compaction that rewrites the minimal command set
- [x] 3. Key expiry: `EXPIRE`, `PEXPIRE`, `TTL`, `PTTL`, `PERSIST`, `SET ... EX/PX`; lazy expiry on access + periodic sampling sweep; injectable clock for tests
- [ ] 4. `ZADD` flags: `NX`, `XX`, `GT`, `LT`, `CH`, `INCR` with Redis's exact compatibility rules and errors
- [ ] 5. More sorted-set commands: `ZPOPMIN`, `ZPOPMAX`, `ZREMRANGEBYRANK`, `ZREMRANGEBYSCORE`, `ZMSCORE`, `ZRANDMEMBER`
- [ ] 6. RESP3 support (`HELLO 3`): maps, doubles, nulls, per-connection protocol so redis-py 8 works without `protocol=2`
- [ ] 7. Benchmark harness (`skipdb.bench`): RankedSkipList vs `TreeMap` vs `ConcurrentSkipListMap` for insert/rank/range at 10k–1M elements, warmup + repeated trials, CSV output, results table in README
- [ ] 8. Single-threaded NIO event-loop server (`--io nio`), Redis's model; compare against thread-per-client with `redis-benchmark` and document throughput/p99
- [ ] 9. Live leaderboard demo: tiny HTTP server (`com.sun.net.httpserver`) + one HTML page that polls SkipDB and animates rank changes; `--demo` flag
- [ ] 10. Dockerfile, architecture diagram (Mermaid) in README, final results section and resume-ready summary
