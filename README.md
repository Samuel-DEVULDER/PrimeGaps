# What is this?

Recreational computing/maths about prime gaps.

A [prime gap](https://www.pzktupel.de/RecordGaps/gapmainpage.php) is the
difference between two consecutive prime numbers. For example, the gap between
23 and 29 is 6. Finding ever-larger gaps — especially ones with a high
[merit](https://en.wikipedia.org/wiki/Prime_gap#Maximal_gaps) — is an active
area of recreational mathematics.

It all started with my answer in a
[fr.sci.math thread](https://nemoweb.net/?DataID=3-X5EACLMWPrVwnkqrBOCRb35Ok@jntp).

# Architecture

The classes form an inheritance chain, each adding a layer of optimization:
```
AbstractPrimeGap
├── NaiveGap
│ ├── SieveGap
│ │ └── ParallelSieveGap
│ └── SIMDGap
└── FactorialGap
```

# Files

### `AbstractPrimeGap.java`
Base class providing the search loop and support infrastructure:
- `searchGaps()` — iterates over increasing target gap sizes, timing and
  reporting each discovery (gap value, prime P, merit, bits, digits)
- `IncreasingBigIntegers` — a memory-efficient, disk-backed collection storing
  the discovered primes as variable-length delta-encoded values (7 bits per
  byte), allowing billions of entries without exhausting RAM
- `passesMillerRabin()` / `isPrime()` — parallel Miller-Rabin primality test
  (100 rounds) combined with `BigInteger.isProbablePrime()` for double-checking
- `nextPrime()` / `prevPrime()` — default prime navigation using
  `BigInteger.nextProbablePrime()`

### `NaiveGap.java`
Simplest concrete implementation. Scans primes one by one using
`BigInteger.nextProbablePrime()` until a gap of the required size is found.
Correct but slow for large gaps — useful as a reference baseline.

### `FactorialGap.java`
Uses the mathematical identity that `n! + k` is composite for all `2 ≤ k ≤ n`,
guaranteeing a gap of at least `n-1` starting just before `n! + 2`. The
implementation computes `prevPrime(n! + 2)` directly — this finds a valid gap
instantly without any search, but the primes grow astronomically fast (n! has
~`n log n` digits), so merit values remain modest.

### `SIMDGap.java`
An alternative prime candidate generator based on **wheel factorization** with
**SIMD filtering** via the Java Vector API (`jdk.incubator.vector`):
- The wheel size and SIMD prime set are computed automatically at startup to
  maximally fill a 512-bit `ByteVector` with small primes that fit in a byte
  after any wheel step increment
- Each candidate is pre-filtered by checking whether any of the SIMD-packed
  small primes divides it — an entire batch of trial divisions in a single
  vector comparison (`ByteVector.compare(EQ, 0).anyTrue()`)
- Supports forward and backward iteration (`ForwardBranch`, `BackwardBranch`,
  `ForwardModulo`, `BackwardModulo`) to allow searching in both directions from
  a starting point
- Requires JVM flag: `--add-modules jdk.incubator.vector`

### `SieveGap.java`
Replaces the naive prime supplier with a **sliding window Sieve of
Eratosthenes** that operates on arbitrary `BigInteger` positions:
- The window is a bit-packed `long[]` (one bit per odd number), covering ~1.28
  billion consecutive integers at a time
- As each prime P ≤ √(window end) is discovered, its odd multiples are
  immediately struck from the current window
- When the window is exhausted it slides forward, reusing the accumulated list
  of small primes to re-sieve the new window
- Bulk extraction: once all marking is done (P > limit), the frozen window is
  scanned via a sequential `IntStream` yielding bit positions directly,
  avoiding per-prime `BigInteger` allocation during the drain phase

Typically **10–100× faster** than `NaiveGap` for large primes.

### `ParallelSieveGap.java`
Extends `SieveGap` by parallelising the two most expensive phases:
- **Marking phase**: small primes (≤ 256) are marked sequentially to avoid
  false sharing; larger primes are marked concurrently using
  `VarHandle.getAndBitwiseOr()` on the `long[]` sieve array
- **Bulk extraction phase**: the frozen window is scanned with a parallel
  `IntStream`, splitting segments across all available cores, followed by a
  parallel sort — yielding primes in order with minimal allocation overhead

Achieves near-linear CPU scaling on the marking phase.

# Records
Up-to-date record list: [here](https://www.pzktupel.de/RecordGaps/risinggap.php).
