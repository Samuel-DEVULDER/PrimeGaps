# What is this?

Recreational computing/maths about prime gaps.

A [prime gap](https://www.pzktupel.de/RecordGaps/gapmainpage.php) is the
difference between two consecutive prime numbers. For example, the gap between
23 and 29 is 6. Finding ever-larger gaps -- especially ones with a high
[merit](https://en.wikipedia.org/wiki/Prime_gap#Maximal_gaps) -- is an active
area of recreational mathematics.

It all started with my answer in a
[fr.sci.math thread](https://nemoweb.net/?DataID=3-X5EACLMWPrVwnkqrBOCRb35Ok@jntp).

# Architecture

The classes form an inheritance chain, each adding a layer of optimization:
```
<AbstractPrimeGap>
|-- BigIntegerNextProbablePrimeGap
|   |-- ParallelMillerRabinGap
|   |   +-- SIMDGap
|   +-- SieveGap
|       |-- <AbstractWheelSieveGap>
|       |   |-- Wheel_210_Sieve
|       |   |   +-- DoubleBufferWheel210SieveGap
|       |   |-- Wheel_2310_Sieve
|       |   |-- Wheel_2_Sieve
|       |   |-- Wheel_30_Sieve
|       |   |   +-- DoubleBufferWheel30SieveGap
|       |   +-- Wheel_6_Sieve
|       |-- DoubleBufferSieveGap
|       |-- ParallelSieveGap
|       |   +-- DoubleBufferParallelSieveGap
|       +-- SIMDSieveGap
|           |-- DoubleBufferSIMDSieveGap
|           +-- ParallelSIMDSieveGap
|               +-- DoubleBufferParallelSIMDSieveGap
+-- FactorialGap
```

# Files

### `AbstractPrimeGap.java`
Base class providing the search loop and support infrastructure:
- `searchGaps()` -- iterates over increasing target gap sizes, timing and
  reporting each discovery (gap value, prime P, merit, bits, digits)
- `IncreasingBigIntegers` -- a memory-efficient, disk-backed collection storing
  the discovered primes as variable-length delta-encoded values (7 bits per
  byte), allowing billions of entries without exhausting RAM
- `passesMillerRabin()` / `isPrime()` -- parallel Miller-Rabin primality test
  (100 rounds) combined with `BigInteger.isProbablePrime()` for double-checking
- `nextPrime()` / `prevPrime()` -- default prime navigation using
  `BigInteger.nextProbablePrime()`

### `NaiveGap.java`
Simplest concrete implementation. Scans primes one by one using
`BigInteger.nextProbablePrime()` until a gap of the required size is found.
Correct but slow for large gaps -- useful as a reference baseline.

Note: the number of occurence of each gap is recorded and an histogram is 
printed at exit.

### `FactorialGap.java`
Uses the mathematical identity that `n! + k` is composite for all `2 <= k <= n`,
guaranteeing a gap of at least `n-1` starting just before `n! + 2`. The
implementation computes `prevPrime(n! + 2)` directly -- this finds a valid gap
instantly without any search, but the primes grow astronomically fast (n! has
~`n log n` digits), so merit values remain modest.

### `SIMDGap.java`
An alternative prime candidate generator based on **wheel factorization** with
**SIMD filtering** via the Java Vector API (`jdk.incubator.vector`):
- The wheel size and SIMD prime set are computed automatically at startup to
  maximally fill a 512-bit `ByteVector` with small primes that fit in a byte
  after any wheel step increment
- Each candidate is pre-filtered by checking whether any of the SIMD-packed
  small primes divides it -- an entire batch of trial divisions in a single
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
- As each prime P <= sqrt(window end) is discovered, its odd multiples are
  immediately struck from the current window
- When the window is exhausted it slides forward, reusing the accumulated list
  of small primes to re-sieve the new window
- Bulk extraction: once all marking is done (P > limit), the frozen window is
  scanned via a sequential `IntStream` yielding bit positions directly,
  avoiding per-prime `BigInteger` allocation during the drain phase

Typically **10x to 100x faster** than `NaiveGap` for large primes.

### `ParallelSieveGap.java`
Extends `SieveGap` by parallelising the two most expensive phases:
- **Marking phase**: small primes (<= 256) are marked sequentially to avoid
  false sharing; larger primes are marked concurrently using
  `VarHandle.getAndBitwiseOr()` on the `long[]` sieve array
- **Bulk extraction phase**: the frozen window is scanned with a parallel
  `IntStream`, splitting segments across all available cores, followed by a
  parallel sort -- yielding primes in order with minimal allocation overhead

Achieves near-linear CPU scaling on the marking phase.

### `WheelSieveGap.java`
A specialization of `SieveGap` that uses wheel factorization to skip over
known composite numbers:
- The wheel size is automatically detected at startup, based on the range of
  small primes that fit in a byte after any wheel step increment
- Supports the same prime navigation and extraction methods as `SieveGap`

### `Wheel2Sieve.java`
Skips all multiples of 2, effectively halving the number of candidates to check.
This is similar to SieveGap but implemented using a wheel object and not hard-coded. Therefore it should run a bit slower that `SieveGap.java`.

### `Wheel6Sieve.java`
Skips all multiples of 2 and 3, reducing the number of candidates to one-third.

### `Wheel30Sieve.java`
Skips all multiples of 2, 3, and 5, leaving only numbers coprime to these primes.

### `Wheel210Sieve.java`
Skips all multiples of 2, 3, 5, and 7, further reducing the candidate set.

### `Wheel2310Sieve.java`
Skips all multiples of 2, 3, 5, 7, and 11, achieving the highest reduction
in candidates among the provided wheel classes. However the data-stucture doesn't fit in  L1/L2 caches and performance gets degraded.

# Benchmarking

Benchmarking is a critical part of this project, as it allows us to measure the performance of different algorithms and optimizations for finding prime gaps. The benchmarking process involves running each implementation on a set of predefined inputs and recording metrics such as execution time, memory usage, and scalability across multiple cores.

The benchmarks are designed to:
- Compare the efficiency of naive and optimized approaches.
- Highlight the impact of parallelization and wheel factorization.
- Identify bottlenecks in the algorithms for further improvement.

Results from the benchmarks provide valuable insights into the trade-offs between simplicity and performance, guiding future development efforts.

## Benchmark Results

The following benchmark results were obtained by testing various implementations on a Windows 11 machine with the following specifications:

**Machine Info:**
- OS      : Windows 11 10.0 (amd64)
- JVM     : Java HotSpot(TM) 64-Bit Server VM 25.0.2
- CPUs    : 4
- RAM     : 2.1 GB total
- CPU     : Intel(R) Core(TM) i7-2640M CPU @ 2.80GHz

**Benchmark Results:**
```
1/10 Testing NaiveGap...4,300,000 primes in 305.6 secs
2/10 Testing SIMDGap...6,147,488 primes in 302.2 secs
3/10 Testing DoubleBufferSieveGap/DoubleBufferWindowedSieve...2,530,600,000 primes in 300.0 secs
4/10 Testing ParallelSieveGap/ParallelWindowedSieve...1,422,100,000 primes in 300.2 secs
5/10 Testing SieveGap/SlidingWindowSieve...1,809,400,000 primes in 300.1 secs
6/10 Testing Wheel210Sieve/WheelSieve...1,343,500,000 primes in 300.4 secs
7/10 Testing Wheel2310Sieve/WheelSieve...917,100,000 primes in 300.0 secs
8/10 Testing Wheel2Sieve/WheelSieve...604,600,000 primes in 300.0 secs
9/10 Testing Wheel30Sieve/OptWheelSieve...942,000,000 primes in 300.0 secs
10/10 Testing Wheel6Sieve/WheelSieve...911,200,000 primes in 300.0 secs
```

**Performance Summary (Primes per Second):**
```
NaiveGap                                                     14,072.2 p/s
SIMDGap                                                      20,342.5 p/s
Wheel2Sieve/WheelSieve                                       2,015,223.0 p/s
Wheel6Sieve/WheelSieve                                       3,037,097.6 p/s
Wheel2310Sieve/WheelSieve                                    3,056,834.3 p/s
Wheel30Sieve/OptWheelSieve                                   3,139,724.1 p/s
Wheel210Sieve/WheelSieve                                     4,472,892.8 p/s
ParallelSieveGap/ParallelWindowedSieve                       4,737,084.8 p/s
SieveGap/SlidingWindowSieve                                  6,029,318.2 p/s
DoubleBufferSieveGap/DoubleBufferWindowedSieve               8,434,487.1 p/s
```

# Records
Up-to-date record list: [here](https://www.pzktupel.de/RecordGaps/risinggap.php).