# Skip List Rectangle Database + SkipDB

This repo contains two things built on skip lists:

1. **SkipDB**: a Redis-compatible in-memory database whose sorted sets run on a
   custom ranked skip list. Works with `redis-cli` and Redis client libraries.
2. **Rectangle database**: the original CS 3114 project (below).

## SkipDB

```bash
mvn -q compile
java -cp target/classes skipdb.SkipDBServer 6380

redis-cli -p 6380 ZADD board 100 alice 85 bob 92 carol
redis-cli -p 6380 ZREVRANGE board 0 2 WITHSCORES   # leaderboard top 3
redis-cli -p 6380 ZREVRANK board carol              # -> 1
redis-cli -p 6380 SET session:42 alice EX 1800 NX   # expires in 30 min
redis-cli -p 6380 TTL session:42                    # -> 1800
```

**Expiry:** any key can carry a TTL (`EXPIRE`/`PEXPIRE`/`EXPIREAT`/`PEXPIREAT`
with Redis 7's `NX XX GT LT`, `TTL PTTL EXPIRETIME PEXPIRETIME PERSIST`, and
`SET ... EX|PX|EXAT|PXAT|KEEPTTL` alongside `NX XX GET`). As in Redis, expired
keys are removed two ways: *lazily*, when a command touches one, and
*actively*, by a cron thread that ten times a second samples 20 random keys
with a TTL (an O(1) dense-array index), deletes the expired ones, and goes
again while more than 25% of a sample had expired (25 ms budget). Writes keep
a key's TTL; `SET` without `KEEPTTL`, `DEL` and emptying a sorted set drop it.
The clock is injectable, so tests drive time explicitly.

**Persistence:** start with `--aof <path>` to keep an append-only file. Every
successful write (`SET DEL FLUSHALL ZADD ZINCRBY ZREM EXPIRE ...`) is appended
as a RESP command and flushed before the reply goes out; the file is replayed
on startup, so data survives a restart or `kill`. Like Redis, relative expiry
is logged as an absolute time (`EXPIRE k 10` → `PEXPIREAT k <ms>`, `SET ... EX`
→ `SET ... PXAT`), and every key that expires is logged as an explicit `DEL`.
Keys never expire *during* replay, so loading the same file a minute or a week
later yields the same keyspace, after which stale keys are reclaimed.

```bash
java -cp target/classes skipdb.SkipDBServer 6380 --aof appendonly.aof --appendfsync everysec
redis-cli -p 6380 BGREWRITEAOF   # compact the log in the background
```

Transactions are logged inside `MULTI`/`EXEC` and only applied on replay once
their `EXEC` is read. A torn last write (crash mid-append) is truncated away on
load, like Redis's `aof-load-truncated yes`; any other corruption refuses to
start.

`--appendfsync` matches Redis's `appendfsync`: `always` fsyncs before every
reply (no acknowledged write is ever lost), `everysec` (default) fsyncs at most
once a second from a background thread so at most ~1 s of writes is at risk,
and `no` leaves it to the OS.

`BGREWRITEAOF` replaces the log with the minimal command set for the current
data (one `SET` per string, one `ZADD` per 64 members, scores written so they
parse back bit-for-bit, a `PEXPIREAT` per key with a TTL, expired keys
skipped). The snapshot is taken under the command lock, then
written to a temp file on a background thread while new writes go to the old
file *and* a rewrite buffer; the buffer is appended, the temp file fsynced and
atomically renamed over the old one. A failed rewrite leaves the old file in
use. A second `BGREWRITEAOF` during a rewrite gets Redis's "already in
progress" error. (Unlike Redis, `BGREWRITEAOF` requires `--aof`.)

```python
import redis  # redis-py; use protocol=2 (SkipDB speaks RESP2)
r = redis.Redis(port=6380, protocol=2, decode_responses=True)
r.zincrby("board", 10, "bob")
r.zrangebyscore("board", 90, "+inf", withscores=True)
```

**How it works**

- `RankedSkipList`: every forward link stores its *span* (how many nodes it
  skips), so summing spans along the search path gives an element's rank.
  Insert, delete, rank, select-by-rank and score lookups are all expected
  O(log n). This is the same design Redis uses for `ZSET`.
- `SortedSet`: hash map (member → score, O(1) `ZSCORE`) + ranked skip list.
- `Resp`: RESP2 parser/encoder, including inline commands and pipelining.
- `SkipDBServer`: thread-per-client TCP server; commands execute under one
  lock, so there are no data races (Redis's single-threaded execution model).
  `MULTI`/`EXEC`/`DISCARD` run queued commands as one atomic batch.
- `Expires`: key → expiry-time index with O(1) random sampling for the
  active-expiry sweep.
- `Aof`: append-only file writer with fsync policies, crash-tolerant
  replayer, and background rewrite (compaction).

**Commands:** `ZADD ZINCRBY ZSCORE ZCARD ZREM ZRANK ZREVRANK ZRANGE ZREVRANGE
ZRANGEBYSCORE ZCOUNT`, `SET GET DEL EXISTS TYPE KEYS DBSIZE FLUSHALL`,
`EXPIRE PEXPIRE EXPIREAT PEXPIREAT TTL PTTL EXPIRETIME PEXPIRETIME PERSIST`,
`PING ECHO INFO HELLO CLIENT SELECT COMMAND`, `MULTI EXEC DISCARD`,
`BGREWRITEAOF`, `QUIT`.

**First numbers** (`redis-benchmark`, 20 clients, dev container; not a tuned
benchmark yet, see the roadmap): ~80k `GET`/`SET` per second and ~44k `ZADD`
per second into a sorted set growing to ~63k members, p50 ≈ 0.2 ms.

**Tests:** 20,000 random operations checked against a `TreeSet` model, RESP
encoding edge cases (UTF-8 byte lengths, malformed input), command semantics,
socket tests for pipelining, transactions, protocol errors and 8
concurrent clients, and AOF tests (restart round-trip, a torn write cut at
every byte offset, unfinished transactions, corrupt files, fsync counts per
policy, rewrites that race with concurrent writes, failed rewrites, exact
score round-trips including `-0` and `inf`), and expiry tests on a fake clock
(TTL rounding, the exact deadline, option conflicts, lazy and active expiry,
and an AOF replayed at three different later times giving the same data). Also verified against real `redis-cli`, `redis-benchmark`
and `redis-py`.

See [ROADMAP.md](ROADMAP.md) for persistence, expiry, benchmarks and the demo.

---

## Rectangle database (original project)

A Java implementation of a **skip list** used as an in-memory database of named
rectangles on a 1024×1024 grid. Commands are read from a text file to insert,
remove, search, and run spatial queries over the rectangles.

![CI](https://github.com/aad-h/SkipList-Rectangle-Database/actions/workflows/ci.yml/badge.svg)

## Features

- Generic `SkipList<K extends Comparable, V>` with duplicate keys, expected
  **O(log n)** insert/search/remove, and an iterator
- Remove by name (key) or by exact coordinates (value)
- `regionsearch`: all rectangles overlapping a query region
- `intersections`: every pair of overlapping rectangles
- Input validation: rectangles must have positive size and lie inside the grid;
  malformed command lines are reported without stopping the run

## Build & run

Requires Java 11+ and Maven. Run all tests with `mvn test`.

```bash
mvn test                                   # run the test suite
mvn package -DskipTests                    # build target/skiplist-rectangles-1.0.0.jar
java -jar target/skiplist-rectangles-1.0.0.jar examples/P1test1.txt
```

## Command format

| Command | Effect |
|---|---|
| `insert <name> <x> <y> <w> <h>` | Add a rectangle (rejected if invalid) |
| `remove <name>` | Remove the first rectangle with that name |
| `remove <x> <y> <w> <h>` | Remove the rectangle with those exact coordinates |
| `regionsearch <x> <y> <w> <h>` | List rectangles that overlap the region |
| `intersections` | List all overlapping pairs |
| `search <name>` | List all rectangles with that name |
| `dump` | Print every skip list node and its height |

Example input (more in `examples/`):

```
insert r1 10 10 5 5
insert r2 15 15 5 5
regionsearch 0 0 20 20
intersections
remove r1
dump
```

## Design

| Class | Responsibility |
|---|---|
| `SkipList` | Generic skip list; node heights are geometric (p = ½, capped at 32). The `Random` is injectable so tests can script node heights. |
| `KVPair` | Key/value pair stored in the list |
| `Rectangle` | Geometry: validity, overlap test (touching edges don't count), `equals`/`hashCode` |
| `Database` | Applies rectangle rules on top of the skip list and prints results |
| `CommandProcessor` | Parses and validates each command line |
| `SkipListProject` | Entry point: reads the command file |

**Complexity:** insert / search / remove-by-name are expected O(log n);
remove-by-coordinates is O(n) (values aren't indexed); `regionsearch` is O(n)
and `intersections` is O(n²).

## Tests

JUnit tests cover every class, including scripted node heights, duplicate-key
removal, console output for each command, malformed input, and a randomized
2,000-operation check of the skip list against a simple list model.

## Background

Originally built for CS 3114 (Data Structures & Algorithms) at Virginia Tech,
then refactored to build with standard Maven/JUnit, fix bugs, and harden input
handling.
