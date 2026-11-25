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
redis-cli -p 6380 ZADD board GT CH 95 bob 90 alice  # -> 1 (only raises scores)
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

**RESP3:** every connection starts in RESP2 and can switch with `HELLO 3`
(redis-py 8 does this by default; `redis-cli -3` too). Replies then use
RESP3's native types exactly as Redis 7 sends them: scores are doubles
(`,2.5`), `WITHSCORES` replies are arrays of `[member, score]` pairs
(`ZPOPMIN key` without a count stays flat, like Redis), `HELLO` is a map,
`INFO` a verbatim string and null is `_`. The protocol is per connection, so
RESP2 and RESP3 clients can share a server. `HELLO` accepts `AUTH default
<any>` and `SETNAME`, with Redis's `NOPROTO`/syntax errors; a failed `HELLO`
leaves the protocol unchanged. Command handlers return protocol-neutral reply
values and the encoder picks the wire form per connection.

```python
import redis  # redis-py 8: negotiates RESP3 automatically
r = redis.Redis(port=6380, decode_responses=True)
r.zincrby("board", 10, "bob")                         # -> 95.5 (a float)
r.zrangebyscore("board", 90, "+inf", withscores=True)  # [('carol', 92.0), ...]
```

**How it works**

- `RankedSkipList`: every forward link stores its *span* (how many nodes it
  skips), so summing spans along the search path gives an element's rank.
  Insert, delete, rank, select-by-rank and score lookups are all expected
  O(log n). This is the same design Redis uses for `ZSET`.
- `SortedSet`: hash map (member → score, O(1) `ZSCORE`) + ranked skip list.
- `Resp`: RESP parser and RESP2/RESP3 encoder (doubles, maps, nulls,
  verbatim strings), including inline commands and pipelining.
- `SkipDBServer`: thread-per-client TCP server; commands execute under one
  lock, so there are no data races (Redis's single-threaded execution model).
  `MULTI`/`EXEC`/`DISCARD` run queued commands as one atomic batch.
- `Expires`: key → expiry-time index with O(1) random sampling for the
  active-expiry sweep.
- `Aof`: append-only file writer with fsync policies, crash-tolerant
  replayer, and background rewrite (compaction).
- `bench.Bench`: micro-benchmark harness comparing `RankedSkipList` with
  `TreeMap` and `ConcurrentSkipListMap` (CSV + Markdown output).

**Commands:** `ZADD` (with `NX XX GT LT CH INCR`, replies and errors matching
Redis 7 byte-for-byte), `ZINCRBY ZSCORE ZCARD ZREM ZRANK ZREVRANK ZRANGE ZREVRANGE
ZRANGEBYSCORE ZCOUNT ZPOPMIN ZPOPMAX ZREMRANGEBYRANK ZREMRANGEBYSCORE ZMSCORE
ZRANDMEMBER` (ZPOPs reach the AOF as a deterministic `ZREM`), `SET GET DEL EXISTS TYPE KEYS DBSIZE FLUSHALL`,
`EXPIRE PEXPIRE EXPIREAT PEXPIREAT TTL PTTL EXPIRETIME PEXPIRETIME PERSIST`,
`PING ECHO INFO HELLO (2|3) CLIENT SELECT COMMAND`, `MULTI EXEC DISCARD`,
`BGREWRITEAOF`, `QUIT`.

**First numbers** (`redis-benchmark`, 20 clients, dev container; not a tuned
benchmark yet, see the roadmap): ~80k `GET`/`SET` per second and ~44k `ZADD`
per second into a sorted set growing to ~63k members, p50 ≈ 0.2 ms.

**Data-structure benchmark** (`skipdb.bench`): the same seeded data
(distinct members, integer scores with many ties) loaded into `RankedSkipList`,
`TreeMap` and `ConcurrentSkipListMap` keyed by (score, member), then random
`ZRANK`-style rank queries, `ZRANGE start start+9` (range by rank) and
`ZRANGEBYSCORE min +inf LIMIT 0 10`. Median ns/op over 5 trials after 2
warmup trials, Java 21, 2-vCPU Xeon @ 2.1 GHz dev container:

| op | n | RankedSkipList | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|--:|
| insert | 10,000 | 607 ns | 175 ns | 366 ns |
| insert | 100,000 | 1082 ns | 391 ns | 744 ns |
| insert | 1,000,000 | 2358 ns | 1068 ns | 1919 ns |
| rank | 10,000 | 582 ns | 45.7 us | 32.5 us |
| rank | 100,000 | 1093 ns | 3.00 ms | 2.53 ms |
| rank | 1,000,000 | 2611 ns | 111.61 ms | 95.25 ms |
| range | 10,000 | 579 ns | 44.1 us | 33.6 us |
| range | 100,000 | 1460 ns | 3.06 ms | 2.48 ms |
| range | 1,000,000 | 3096 ns | 108.53 ms | 96.89 ms |
| rangebyscore | 10,000 | 384 ns | 863 ns | 612 ns |
| rangebyscore | 100,000 | 1252 ns | 1364 ns | 1445 ns |
| rangebyscore | 1,000,000 | 3599 ns | 3936 ns | 4941 ns |

The spans are the whole story: the JDK maps keep no subtree sizes, so rank and
select-by-rank walk the entries (O(rank)) and fall ~40,000× behind at a
million entries, while score-range queries, which only need ordered search,
are a tie. Plain inserts are where the JDK wins: `TreeMap` is 2–3.5× faster
and `ConcurrentSkipListMap` 1.2–1.7× faster, since `RankedSkipList.insert`
also maintains spans and allocates two 32-slot scratch arrays per call (an
obvious thing to optimize). This is a hand-rolled harness rather than JMH
(Maven Central was unreachable when it was written): it guards against
dead-code elimination with a volatile sink, forces a GC and rotates the
structure order every trial, and stops a read batch after `--budget-ms`
(default 500 ms), so the O(n) rank/range figures for the JDK maps at 1M come
from 8 queries per trial; the CSV records how many ops each number covers.

```bash
java -Xmx2g -cp target/classes skipdb.bench.Bench \
    --sizes 10000,100000,1000000 --trials 5 --warmup 2 --queries 1000 --csv bench.csv
```

**Tests:** 20,000 random operations checked against a `TreeSet` model, RESP
encoding edge cases (UTF-8 byte lengths, malformed input), command semantics,
socket tests for pipelining, transactions, protocol errors and 8
concurrent clients, and AOF tests (restart round-trip, a torn write cut at
every byte offset, unfinished transactions, corrupt files, fsync counts per
policy, rewrites that race with concurrent writes, failed rewrites, exact
score round-trips including `-0` and `inf`), and expiry tests on a fake clock
(TTL rounding, the exact deadline, option conflicts, lazy and active expiry,
and an AOF replayed at three different later times giving the same data), and
RESP3 tests (encoder bytes for every type, `HELLO` validation, one connection
in RESP3 beside one in RESP2, transactions). RESP3 replies were diffed
byte-for-byte against Redis 7.0.15. Benchmark tests check that all three
benchmarked structures return identical ranks and ranges, plus flag parsing,
the time budget and the output formats. Also verified against real `redis-cli`,
`redis-benchmark` and `redis-py` 8 (default RESP3).

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
