# Skip List Rectangle Database

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

Requires Java 11+ and Maven.

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
