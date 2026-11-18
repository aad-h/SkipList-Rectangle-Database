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
```

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

**Commands:** `ZADD ZINCRBY ZSCORE ZCARD ZREM ZRANK ZREVRANK ZRANGE ZREVRANGE
ZRANGEBYSCORE ZCOUNT`, `SET GET DEL EXISTS TYPE KEYS DBSIZE FLUSHALL`,
`PING ECHO INFO HELLO CLIENT SELECT COMMAND`, `MULTI EXEC DISCARD`, `QUIT`.

**First numbers** (`redis-benchmark`, 20 clients, dev container; not a tuned
benchmark yet, see the roadmap): ~80k `GET`/`SET` per second and ~44k `ZADD`
per second into a sorted set growing to ~63k members, p50 ≈ 0.2 ms.

**Tests:** 20,000 random operations checked against a `TreeSet` model, RESP
encoding edge cases (UTF-8 byte lengths, malformed input), command semantics,
and socket tests for pipelining, transactions, protocol errors and 8
concurrent clients. Also verified against real `redis-cli`, `redis-benchmark`
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
