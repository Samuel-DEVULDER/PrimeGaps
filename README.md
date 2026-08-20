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

## Longest run
```=== Detection JVM ===
Vendor  : unknown
Version : 0
CPUs    : 28

[Mode] HotSpot C2 standard
[Classe] primegap.sieve.parallel.ParallelPrimesSieveGap$FF$DB
=====================================================

java -XX:+UnlockExperimentalVMOptions --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -XX:+EnableVectorSupport -XX:+OptimizeFill -XX:+DoEscapeAnalysis -XX:+EliminateLocks -XX:ReservedCodeCacheSize=256m -XX:+TieredCompilation -XX:CompileThreshold=1000 -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch -Xms256m -cp "E:\nosave\data\WS_dev\PrimeGaps\bin" primegap.sieve.parallel.ParallelPrimesSieveGap$FF$DB

OpenJDK 64-Bit Server VM warning: Ignoring option ZGenerational; support was removed in 24.0
WARNING: Using incubator modules: jdk.incubator.vector
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 2...found.
>> 2
 + 3
 = 5
0.136s (tot=0s), 2 bits, 1 digits, x3.0 prev, +1.82 merit, ~3.00000, 14 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 4...found.
>> 4
 + 7
 = 11
0.065s (tot=0s), 3 bits, 1 digits, x2.3 prev, +2.06 merit, ~7.00000, 19 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 6...found.
>> 6
 + 23
 = 29
0.125s (tot=0s), 5 bits, 2 digits, x3.3 prev, 1.91 merit, ~23.0000, 27 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 8...found.
>> 8
 + 89
 = 97
0.226s (tot=0s), 7 bits, 2 digits, x3.9 prev, 1.78 merit, ~89.0000, 43 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 10...found.
>> 14
 + 113
 = 127
0.047s (tot=0s), 7 bits, 3 digits, x1.3 prev, +2.96 merit, ~113.000, 50 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 16...found.
>> 18
 + 523
 = 541
0.233s (tot=0s), 10 bits, 3 digits, x4.6 prev, 2.88 merit, ~523.000, 118 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 20...found.
>> 20
 + 887
 = 907
0.096s (tot=0s), 10 bits, 3 digits, x1.7 prev, 2.95 merit, ~887.000, 165 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 22...found.
>> 22
 + 1 129
 = 1 151
0.049s (tot=0s), 11 bits, 4 digits, x1.3 prev, +3.13 merit, ~1129.00, 193 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 24...found.
>> 34
 + 1 327
 = 1 361
0.036s (tot=1s), 11 bits, 4 digits, x1.2 prev, +4.73 merit, ~1327.00, 214 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 36...found.
>> 36
 + 9 551
 = 9 587
0.373s (tot=1s), 14 bits, 4 digits, x7.2 prev, 3.93 merit, ~9551.00, 853 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 38...found.
>> 44
 + 15 683
 = 15 727
0.084s (tot=1s), 14 bits, 5 digits, x1.6 prev, 4.55 merit, ~15683.0, 1 244 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 46...found.
>> 52
 + 19 609
 = 19 661
0.034s (tot=1s), 15 bits, 5 digits, x1.3 prev, +5.26 merit, ~19609.0, 1 478 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 54...found.
>> 72
 + 31 397
 = 31 469
0.022s (tot=1s), 15 bits, 5 digits, x1.6 prev, +6.95 merit, ~31397.0, 2 217 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 74...found.
>> 86
 + 155 921
 = 156 007
0.003s (tot=1s), 18 bits, 6 digits, x5.0 prev, +7.19 merit, ~155921, 9 385 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 88...found.
>> 96
 + 360 653
 = 360 749
0.002s (tot=1s), 19 bits, 6 digits, x2.3 prev, +7.50 merit, ~360653, 20 103 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 98...found.
>> 112
 + 370 261
 = 370 373
0.000s (tot=1s), 19 bits, 6 digits, x1.0 prev, +8.74 merit, ~370261, 20 586 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 114...found.
>> 114
 + 492 113
 = 492 227
0.001s (tot=1s), 19 bits, 6 digits, x1.3 prev, 8.70 merit, ~492113, 26 690 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 116...found.
>> 118
 + 1 349 533
 = 1 349 651
0.007s (tot=1s), 21 bits, 7 digits, x2.7 prev, 8.36 merit, ~1.34953e+06, 67 209 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 120...found.
>> 132
 + 1 357 201
 = 1 357 333
0.000s (tot=1s), 21 bits, 7 digits, x1.0 prev, +9.35 merit, ~1.35720e+06, 67 561 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 134...found.
>> 148
 + 2 010 733
 = 2 010 881
0.004s (tot=1s), 21 bits, 7 digits, x1.5 prev, +10.20 merit, ~2.01073e+06, 96 918 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 150...found.
>> 154
 + 4 652 353
 = 4 652 507
0.013s (tot=1s), 23 bits, 7 digits, x2.3 prev, 10.03 merit, ~4.65235e+06, 209 277 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 156...found.
>> 180
 + 17 051 707
 = 17 051 887
0.062s (tot=1s), 25 bits, 8 digits, x3.7 prev, +10.81 merit, ~1.70517e+07, 675 867 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 182...found.
>> 210
 + 20 831 323
 = 20 831 533
0.022s (tot=1s), 25 bits, 8 digits, x1.2 prev, +12.46 merit, ~2.08313e+07, 804 325 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 212...found.
>> 220
 + 47 326 693
 = 47 326 913
0.150s (tot=1s), 26 bits, 8 digits, x2.3 prev, 12.45 merit, ~4.73267e+07, 1 591 189 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 222...found.
>> 222
 + 122 164 747
 = 122 164 969
0.220s (tot=2s), 27 bits, 9 digits, x2.6 prev, 11.92 merit, ~1.22165e+08, 3 458 715 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 224...found.
>> 234
 + 189 695 659
 = 189 695 893
0.312s (tot=2s), 28 bits, 9 digits, x1.6 prev, 12.28 merit, ~1.89696e+08, 4 535 782 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 236...found.
>> 248
 + 191 912 783
 = 191 913 031
0.010s (tot=2s), 28 bits, 9 digits, x1.0 prev, +13.00 merit, ~1.91913e+08, 4 566 051 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 250...found.
>> 250
 + 387 096 133
 = 387 096 383
0.531s (tot=2s), 29 bits, 9 digits, x2.0 prev, 12.64 merit, ~3.87096e+08, 7 220 885 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 252...found.
>> 282
 + 436 273 009
 = 436 273 291
0.224s (tot=3s), 29 bits, 9 digits, x1.1 prev, +14.18 merit, ~4.36273e+08, 7 500 862 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 284...found.
>> 288
 + 1 294 268 491
 = 1 294 268 779
5.079s (tot=8s), 31 bits, 10 digits, x3.0 prev, 13.73 merit, ~1.29427e+09, 7 953 057 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 290...found.
>> 292
 + 1 453 168 141
 = 1 453 168 433
0.481s (tot=8s), 31 bits, 10 digits, x1.1 prev, 13.84 merit, ~1.45317e+09, 8 384 141 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 294...found.
>> 320
 + 2 300 942 549
 = 2 300 942 869
2.430s (tot=11s), 32 bits, 10 digits, x1.6 prev, +14.84 merit, ~2.30094e+09, 10 130 982 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 322...found.
>> 336
 + 3 842 610 773
 = 3 842 611 109
0.525s (tot=11s), 32 bits, 10 digits, x1.7 prev, +15.22 merit, ~3.84261e+09, 15 757 179 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 338...found.
>> 354
 + 4 302 407 359
 = 4 302 407 713
0.088s (tot=11s), 33 bits, 10 digits, x1.1 prev, +15.96 merit, ~4.30241e+09, 17 416 266 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 356...found.
>> 382
 + 10 726 904 659
 = 10 726 905 041
2.869s (tot=14s), 34 bits, 11 digits, x2.5 prev, +16.54 merit, ~1.07269e+10, 33 415 091 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 384...found.
>> 384
 + 20 678 048 297
 = 20 678 048 681
4.877s (tot=19s), 35 bits, 11 digits, x1.9 prev, 16.17 merit, ~2.06780e+10, 46 855 622 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 386...found.
>> 394
 + 22 367 084 959
 = 22 367 085 353
0.614s (tot=20s), 35 bits, 11 digits, x1.1 prev, 16.53 merit, ~2.23671e+10, 48 960 578 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 396...found.
>> 456
 + 25 056 082 087
 = 25 056 082 543
1.197s (tot=21s), 35 bits, 11 digits, x1.1 prev, +19.04 merit, ~2.50561e+10, 51 501 313 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 458...found.
>> 464
 + 42 652 618 343
 = 42 652 618 807
9.190s (tot=30s), 36 bits, 11 digits, x1.7 prev, 18.96 merit, ~4.26526e+10, 59 807 729 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 466...found.
>> 468
 + 127 976 334 671
 = 127 976 335 139
48.762s (tot=1m 19s), 37 bits, 12 digits, x3.0 prev, 18.30 merit, ~1.27976e+11, 65 870 937 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 470...found.
>> 474
 + 182 226 896 239
 = 182 226 896 713
32.584s (tot=1m 51s), 38 bits, 12 digits, x1.4 prev, 18.28 merit, ~1.82227e+11, 65 508 562 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 476...found.
>> 486
 + 241 160 624 143
 = 241 160 624 629
37.897s (tot=2m 29s), 38 bits, 12 digits, x1.3 prev, 18.54 merit, ~2.41161e+11, 64 022 598 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 488...found.
>> 490
 + 297 501 075 799
 = 297 501 076 289
38.619s (tot=3m 8s), 39 bits, 12 digits, x1.2 prev, 18.55 merit, ~2.97501e+11, 62 261 090 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 492...found.
>> 500
 + 303 371 455 241
 = 303 371 455 741
3.624s (tot=3m 11s), 39 bits, 12 digits, x1.0 prev, 18.91 merit, ~3.03371e+11, 62 242 900 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 502...found.
>> 514
 + 304 599 508 537
 = 304 599 509 051
0.812s (tot=3m 12s), 39 bits, 12 digits, x1.0 prev, +19.44 merit, ~3.04600e+11, 62 221 777 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 516...found.
>> 516
 + 416 608 695 821
 = 416 608 696 337
77.458s (tot=4m 30s), 39 bits, 12 digits, x1.4 prev, 19.29 merit, ~4.16609e+11, 59 964 834 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 518...found.
>> 532
 + 461 690 510 011
 = 461 690 510 543
31.188s (tot=5m 1s), 39 bits, 12 digits, x1.1 prev, +19.81 merit, ~4.61691e+11, 59 339 406 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 534...found.
>> 534
 + 614 487 453 523
 = 614 487 454 057
106.160s (tot=6m 47s), 40 bits, 12 digits, x1.3 prev, 19.67 merit, ~6.14487e+11, 57 764 132 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 536...found.
>> 540
 + 738 832 927 927
 = 738 832 928 467
86.664s (tot=8m 14s), 40 bits, 12 digits, x1.2 prev, 19.76 merit, ~7.38833e+11, 56 871 632 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 542...found.
>> 582
 + 1 346 294 310 749
 = 1 346 294 311 331
456.899s (tot=15m 51s), 41 bits, 13 digits, x1.8 prev, +20.84 merit, ~1.34629e+12, 52 644 235 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 584...found.
>> 588
 + 1 408 695 493 609
 = 1 408 695 494 197
46.465s (tot=16m 37s), 41 bits, 13 digits, x1.0 prev, +21.02 merit, ~1.40870e+12, 52 430 074 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 590...found.
>> 602
 + 1 968 188 556 461
 = 1 968 188 557 063
418.724s (tot=23m 36s), 41 bits, 13 digits, x1.4 prev, +21.27 merit, ~1.96819e+12, 50 962 627 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 604...found.
>> 652
 + 2 614 941 710 599
 = 2 614 941 711 251
479.464s (tot=31m 35s), 42 bits, 13 digits, x1.3 prev, +22.80 merit, ~2.61494e+12, 50 062 048 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 654...found.
>> 674
 + 7 177 162 611 713
 = 7 177 162 612 387
3469.396s (tot=1h 29m 25s), 43 bits, 13 digits, x2.7 prev, 22.77 merit, ~7.17716e+12, 46 832 671 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 676...found.
>> 716
 + 13 829 048 559 701
 = 13 829 048 560 417
5058.912s (tot=2h 53m 44s), 44 bits, 14 digits, x1.9 prev, +23.66 merit, ~1.38290e+13, 45 400 542 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 718...found.
>> 766
 + 19 581 334 192 423
 = 19 581 334 193 189
4436.707s (tot=4h 7m 40s), 45 bits, 14 digits, x1.4 prev, +25.03 merit, ~1.95813e+13, 44 561 657 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 768...found.
>> 778
 + 42 842 283 925 351
 = 42 842 283 926 129
18047.186s (tot=9h 8m 27s), 46 bits, 14 digits, x2.2 prev, 24.79 merit, ~4.28423e+13, 42 891 173 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 780...found.
>> 804
 + 90 874 329 411 493
 = 90 874 329 412 297
38498.256s (tot=19h 50m 6s), 47 bits, 14 digits, x2.1 prev, 25.02 merit, ~9.08743e+13, 40 912 950 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 806...found.
>> 806
 + 171 231 342 420 521
 = 171 231 342 421 327
67695.390s (tot=1d 14h 38m 21s), 48 bits, 15 digits, x1.9 prev, 24.59 merit, ~1.71231e+14, 38 782 891 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 808...found.
>> 906
 + 218 209 405 436 543
 = 218 209 405 437 449
39211.015s (tot=2d 1h 31m 52s), 48 bits, 15 digits, x1.3 prev, +27.44 merit, ~2.18209e+14, 38 262 388 p/s.
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 90 1w 4d 18h 8m 23s, 1 155 754 560 061 443, ~33.7, 33 813 477/s
```

# Records
Up-to-date record list: [here](https://www.pzktupel.de/RecordGaps/risinggap.php).