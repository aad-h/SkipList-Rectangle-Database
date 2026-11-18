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
- [ ] Append-only file (AOF) with `everysec` / `always` fsync, replay on startup
- [ ] AOF rewrite/compaction
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
