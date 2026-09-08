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
## Lowend machine
Thomas R. Nicely's 1996 result in ~15 days.
```
=== Detection JVM ===
Vendor  : Microsoft-13877124
Version : 25
CPUs    : 4

[Mode] HotSpot C2 standard
[Classe] primegap.sieve.SieveGap$FF$DB
=====================================================

java -XX:+UnlockExperimentalVMOptions --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -XX:+EnableVectorSupport -XX:+OptimizeFill -XX:+DoEscapeAnalysis -XX:+EliminateLocks -XX:ReservedCodeCacheSize=256m -XX:+TieredCompilation -XX:CompileThreshold=1000 -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch -Xms256m -cp "C:\Users\Samuel\git\PrimeGaps\bin" primegap.sieve.SieveGap$FF$DB

OpenJDK 64-Bit Server VM warning: Ignoring option ZGenerational; support was removed in 24.0
WARNING: Using incubator modules: jdk.incubator.vector
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 2... 0s, 3, ~Infinity, 0/s, 0,0%found.←[K
>> 2
 + 3
 = 5
0.027s (tot=0s), 2 bits, 1 digits, x3.0 prev, +1.82 merit, ~3.00000, 73 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 4...found.←[K
>> 4
 + 7
 = 11
0.005s (tot=0s), 3 bits, 1 digits, x2.3 prev, +2.06 merit, ~7.00000, 121 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 6...found.←[K
>> 6
 + 23
 = 29
0.008s (tot=0s), 5 bits, 2 digits, x3.3 prev, 1.91 merit, ~23.0000, 222 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 8...found.←[K
>> 8
 + 89
 = 97
0.019s (tot=0s), 7 bits, 2 digits, x3.9 prev, 1.78 merit, ~89.0000, 406 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 10...found.←[K
>> 14
 + 113
 = 127
0.001s (tot=0s), 7 bits, 3 digits, x1.3 prev, +2.96 merit, ~113.000, 495 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 16...found.←[K
>> 18
 + 523
 = 541
0.012s (tot=0s), 10 bits, 3 digits, x4.6 prev, 2.88 merit, ~523.000, 1 369 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 20...found.←[K
>> 20
 + 887
 = 907
0.007s (tot=0s), 10 bits, 3 digits, x1.7 prev, 2.95 merit, ~887.000, 1 937 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 22...found.←[K
>> 22
 + 1 129
 = 1 151
0.002s (tot=0s), 11 bits, 4 digits, x1.3 prev, +3.13 merit, ~1129.00, 2 323 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 24...found.←[K
>> 34
 + 1 327
 = 1 361
0.002s (tot=0s), 11 bits, 4 digits, x1.2 prev, +4.73 merit, ~1327.00, 2 619 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 36...found.←[K
>> 36
 + 9 551
 = 9 587
0.014s (tot=0s), 14 bits, 4 digits, x7.2 prev, 3.93 merit, ~9551.00, 12 262 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 38...found.←[K
>> 44
 + 15 683
 = 15 727
0.001s (tot=0s), 14 bits, 5 digits, x1.6 prev, 4.55 merit, ~15683.0, 18 730 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 46...found.←[K
>> 52
 + 19 609
 = 19 661
0.001s (tot=0s), 15 bits, 5 digits, x1.3 prev, +5.26 merit, ~19609.0, 22 566 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 54...found.←[K
>> 72
 + 31 397
 = 31 469
0.002s (tot=0s), 15 bits, 5 digits, x1.6 prev, +6.95 merit, ~31397.0, 33 770 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 74...found.←[K
>> 86
 + 155 921
 = 156 007
0.015s (tot=0s), 18 bits, 6 digits, x5.0 prev, +7.19 merit, ~155921, 124 709 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 88...found.←[K
>> 96
 + 360 653
 = 360 749
0.018s (tot=0s), 19 bits, 6 digits, x2.3 prev, +7.50 merit, ~360653, 231 116 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 98...found.←[K
>> 112
 + 370 261
 = 370 373
0.000s (tot=0s), 19 bits, 6 digits, x1.0 prev, +8.74 merit, ~370261, 236 130 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 114...found.←[K
>> 114
 + 492 113
 = 492 227
0.003s (tot=0s), 19 bits, 6 digits, x1.3 prev, 8.70 merit, ~492113, 300 265 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 116...found.←[K
>> 118
 + 1 349 533
 = 1 349 651
0.020s (tot=0s), 21 bits, 7 digits, x2.7 prev, 8.36 merit, ~1.34953e+06, 661 053 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 120...found.←[K
>> 132
 + 1 357 201
 = 1 357 333
0.000s (tot=0s), 21 bits, 7 digits, x1.0 prev, +9.35 merit, ~1.35720e+06, 662 821 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 134...found.←[K
>> 148
 + 2 010 733
 = 2 010 881
0.018s (tot=0s), 21 bits, 7 digits, x1.5 prev, +10.20 merit, ~2.01073e+06, 854 018 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 150...found.←[K
>> 154
 + 4 652 353
 = 4 652 507
0.049s (tot=0s), 23 bits, 7 digits, x2.3 prev, 10.03 merit, ~4.65235e+06, 1 453 890 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 156...found.←[K
>> 180
 + 17 051 707
 = 17 051 887
0.455s (tot=0s), 25 bits, 8 digits, x3.7 prev, +10.81 merit, ~1.70517e+07, 1 611 387 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 182...found.←[K
>> 210
 + 20 831 323
 = 20 831 533
0.091s (tot=0s), 25 bits, 8 digits, x1.2 prev, +12.46 merit, ~2.08313e+07, 1 713 342 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 212...found.←[K
>> 220
 + 47 326 693
 = 47 326 913
0.487s (tot=1s), 26 bits, 8 digits, x2.3 prev, 12.45 merit, ~4.73267e+07, 2 266 845 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 222...found.←[K
>> 222
 + 122 164 747
 = 122 164 969
1.144s (tot=2s), 27 bits, 9 digits, x2.6 prev, 11.92 merit, ~1.22165e+08, 2 897 379 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 224...found.←[K217 731, ~17.8, 2 581 640/s, 7,5%
>> 234
 + 189 695 659
 = 189 695 893
0.957s (tot=3s), 28 bits, 9 digits, x1.6 prev, 12.28 merit, ~1.89696e+08, 3 138 133 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 236...found.←[K
>> 248
 + 191 912 783
 = 191 913 031
0.026s (tot=3s), 28 bits, 9 digits, x1.0 prev, +13.00 merit, ~1.91913e+08, 3 148 473 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 250...found.←[K544 323, ~18.6, 2 902 109/s, 16,2%
>> 250
 + 387 096 133
 = 387 096 383
2.922s (tot=6s), 29 bits, 9 digits, x2.0 prev, 12.64 merit, ~3.87096e+08, 3 280 062 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 252...found.←[K
>> 282
 + 436 273 009
 = 436 273 291
0.630s (tot=6s), 29 bits, 9 digits, x1.1 prev, +14.18 merit, ~4.36273e+08, 3 339 574 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 284...found.←[K75 068 419, ~19.9, 3 520 563/s, 57,3%
>> 288
 + 1 294 268 491
 = 1 294 268 779
10.907s (tot=17s), 31 bits, 10 digits, x3.0 prev, 13.73 merit, ~1.29427e+09, 3 640 449 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 290...found.←[K
>> 292
 + 1 453 168 141
 = 1 453 168 433
1.984s (tot=19s), 31 bits, 10 digits, x1.1 prev, 13.84 merit, ~1.45317e+09, 3 657 122 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 294...found.←[K30 043 139, ~20.4, 3 670 971/s, 89,2%
>> 320
 + 2 300 942 549
 = 2 300 942 869
9.590s (tot=29s), 32 bits, 10 digits, x1.6 prev, +14.84 merit, ~2.30094e+09, 3 815 155 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 322...found.←[K70 893 827, ~20.9, 4 577 578/s
>> 336
 + 3 842 610 773
 = 3 842 611 109
5.660s (tot=35s), 32 bits, 10 digits, x1.7 prev, +15.22 merit, ~3.84261e+09, 5 212 151 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 338...found.←[K76 200 195, ~21.1, 5 211 501/s
>> 354
 + 4 302 407 359
 = 4 302 407 713
1.650s (tot=36s), 33 bits, 10 digits, x1.1 prev, +15.96 merit, ~4.30241e+09, 5 543 680 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 356...found.←[K0 217 324 547, ~22.0, 7 705 920/s
>> 382
 + 10 726 904 659
 = 10 726 905 041
24.786s (tot=1m 1s), 34 bits, 11 digits, x2.5 prev, +16.54 merit, ~1.07269e+10, 7 908 749 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 384...found.←[K20 367 540 227, ~22.7, 9 042 137/s
>> 384
 + 20 678 048 297
 = 20 678 048 681
38.235s (tot=1m 39s), 35 bits, 11 digits, x1.9 prev, 16.17 merit, ~2.06780e+10, 9 129 971 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 386...found.←[K21 927 821 315, ~22.8, 9 146 481/s
>> 394
 + 22 367 084 959
 = 22 367 085 353
6.655s (tot=1m 46s), 35 bits, 11 digits, x1.1 prev, 16.53 merit, ~2.23671e+10, 9 226 136 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 396...found.←[K24 964 497 411, ~22.9, 9 298 295/s
>> 456
 + 25 056 082 087
 = 25 056 082 543
10.488s (tot=1m 56s), 35 bits, 11 digits, x1.1 prev, +19.04 merit, ~2.50561e+10, 9 361 304 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 458...found.←[K2 547 019 779, ~23.4, 9 749 891/s
>> 464
 + 42 652 618 343
 = 42 652 618 807
69.064s (tot=3m 5s), 36 bits, 11 digits, x1.7 prev, 18.96 merit, ~4.26526e+10, 9 789 409 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 466...found.←[K127 792 054 275, ~24.5, 9 806 592/s
>> 468
 + 127 976 334 671
 = 127 976 335 139
345.262s (tot=8m 51s), 37 bits, 12 digits, x3.0 prev, 18.30 merit, ~1.27976e+11, 9 820 730 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 470...found.←[K 181 563 031 555, ~24.9, 9 687 572/s
>> 474
 + 182 226 896 239
 = 182 226 896 713
223.987s (tot=12m 35s), 38 bits, 12 digits, x1.4 prev, 18.28 merit, ~1.82227e+11, 9 696 427 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 476...found.←[K 241 105 371 139, ~25.2, 9 587 761/s
>> 486
 + 241 160 624 143
 = 241 160 624 629
243.544s (tot=16m 38s), 38 bits, 12 digits, x1.3 prev, 18.54 merit, ~2.41161e+11, 9 594 959 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 488...found.←[K 297 376 153 603, ~25.4, 9 502 468/s
>> 490
 + 297 501 075 799
 = 297 501 076 289
234.260s (tot=20m 33s), 39 bits, 12 digits, x1.2 prev, 18.55 merit, ~2.97501e+11, 9 508 240 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 492...found.←[K 303 147 515 907, ~25.4, 9 494 492/s
>> 500
 + 303 371 455 241
 = 303 371 455 741
24.453s (tot=20m 57s), 39 bits, 12 digits, x1.0 prev, 18.91 merit, ~3.03371e+11, 9 499 986 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 502...found.←[K304 556 802 051, ~25.4, 9 491 922/s
>> 514
 + 304 599 508 537
 = 304 599 509 051
5.169s (tot=21m 2s), 39 bits, 12 digits, x1.0 prev, +19.44 merit, ~3.04600e+11, 9 497 881 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 516...found.←[K 416 108 511 235, ~25.7, 9 396 575/s
>> 516
 + 416 608 695 821
 = 416 608 696 337
460.856s (tot=28m 43s), 39 bits, 12 digits, x1.4 prev, 19.29 merit, ~4.16609e+11, 9 400 770 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 518...found.←[K 461 289 553 923, ~25.8, 9 363 813/s
>> 532
 + 461 690 510 011
 = 461 690 510 543
185.631s (tot=31m 49s), 39 bits, 12 digits, x1.1 prev, +19.81 merit, ~4.61691e+11, 9 367 565 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 534...found.←[K 614 029 328 387, ~26.1, 9 257 008/s
>> 534
 + 614 487 453 523
 = 614 487 454 057
633.222s (tot=42m 22s), 40 bits, 12 digits, x1.3 prev, 19.67 merit, ~6.14487e+11, 9 259 692 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 536...found.←[K738 348 498 947, ~26.3, 9 169 404/s
>> 540
 + 738 832 927 927
 = 738 832 928 467
522.113s (tot=51m 4s), 40 bits, 12 digits, x1.2 prev, 19.76 merit, ~7.38833e+11, 9 171 702 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 542...found.←[K7s, 1 345 666 940 931, ~26.9, 8 831 015/s
>> 582
 + 1 346 294 310 749
 = 1 346 294 311 331
2604.697s (tot=1h 34m 29s), 41 bits, 13 digits, x1.8 prev, +20.84 merit, ~1.34629e+12, 8 832 032 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 584...found.←[Ks, 1 408 598 278 147, ~26.9, 8 799 701/s
>> 588
 + 1 408 695 493 609
 = 1 408 695 494 197
273.716s (tot=1h 39m 2s), 41 bits, 13 digits, x1.0 prev, +21.02 merit, ~1.40870e+12, 8 800 906 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 590...found.←[K2s, 1 967 615 115 267, ~27.3, 8 639 300/s
>> 602
 + 1 968 188 556 461
 = 1 968 188 557 063
2410.990s (tot=2h 19m 13s), 41 bits, 13 digits, x1.4 prev, +21.27 merit, ~1.96819e+12, 8 640 076 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 604...found.←[Ks, 2 614 913 662 979, ~27.6, 8 565 918/s
>> 652
 + 2 614 941 710 599
 = 2 614 941 711 251
2724.718s (tot=3h 4m 38s), 42 bits, 13 digits, x1.3 prev, +22.80 merit, ~2.61494e+12, 8 566 570 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 654...found.←[K0s, 7 176 621 916 163, ~28.6, 8 154 590/s
>> 674
 + 7 177 162 611 713
 = 7 177 162 612 387
19733.346s (tot=8h 33m 31s), 43 bits, 13 digits, x2.7 prev, 22.77 merit, ~7.17716e+12, 8 154 765 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 676...found.←[Ks, 13 828 737 728 515, ~29.2, 7 708 951/s
>> 716
 + 13 829 048 559 701
 = 13 829 048 560 417
30577.935s (tot=17h 3m 9s), 44 bits, 14 digits, x1.9 prev, +23.66 merit, ~1.38290e+13, 7 709 045 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 718... 1d 0h←[Km 3s, 19 581 326 327 811, ~29.6, 7 427 017/s   found.
>> 766
 + 19 581 334 192 423
 = 19 581 334 193 189
27772.790s (tot=1d 0h 46m 2s), 45 bits, 14 digits, x1.4 prev, +25.03 merit, ~1.95813e+13, 7 427 091 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 768...found.←[K8m 42s, 42 841 694 797 827, ~30.4, 6 704 460/s
>> 778
 + 42 842 283 925 351
 = 42 842 283 926 129
121362.025s (tot=2d 10h 28m 44s), 46 bits, 14 digits, x2.2 prev, 24.79 merit, ~4.28423e+13, 6 704 474 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 780...found.←[Km 24s, 90 873 857 572 867, ~31.1, 6 004 913/s
>> 804
 + 90 874 329 411 493
 = 90 874 329 412 297
275981.564s (tot=5d 15h 8m 26s), 47 bits, 14 digits, x2.1 prev, 25.02 merit, ~9.08743e+13, 6 004 920 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 806...found.←[Kh 29m 3s, 171 231 202 508 803, ~31.7, 5 400 449/s
>> 806
 + 171 231 342 420 521
 = 171 231 342 421 327
512437.249s (tot=1w 4d 13h 29m 3s), 48 bits, 15 digits, x1.9 prev, 24.59 merit, ~1.71231e+14, 5 400 453 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 808...found.←[K 48m 6s, 218 209 252 802 563, ~32.0, 5 152 717/s
>> 906
 + 218 209 405 436 543
 = 218 209 405 437 449
325143.105s (tot=2w 1d 7h 48m 6s), 48 bits, 15 digits, x1.3 prev, +27.44 merit, ~2.18209e+14, 5 152 720 p/s.
SieveGap/DoubleBuffer/FastFoward: Searching gap >= 908... 2w 3d 3h 37m 51s, 240 167 138 885 635, ~32.1, 5 052 207/s
```

### Statistics
```
=== Machine Info ===
OS      : Windows 10 10.0 (amd64)
JVM     : OpenJDK 64-Bit Server VM 25.0.3 (Microsoft)
CPUs    : 4
RAM     : 1.9 GB total
L2CacheSize  L3CacheSize  Name
4096         0            AMD A10-6700 APU with Radeon(tm) HD Graphics
====================

=== Statistics (SieveGap/DoubleBuffer/FastFoward) ===
#Primes              : 7,487,292,034,707
Time                 : 1,482,023.5 secs (2w 3d 3h 40m 23s)
Speed                : 5,052,073.7 primes/sec
Biggest prime        : 218209405437449 (~2.182e+14)
Best prime (merit)   : 218209405436543 (27.4)

gap                 prime  digits  merit
----------------------------------------
2                       3       1   1.82
4                       7       1   2.06
6                      23       2   1.91
8                      89       2   1.78
10                    139       3   2.03
12                    199       3   2.27
14                    113*      3   2.96
16                  1 831       4   2.13
18                    523*      3   2.88
20                    887       3   2.95
22                  1 129       4   3.13
24                  1 669       4   3.23
26                  2 477       4   3.33
28                  2 971       4   3.50
30                  4 297       4   3.59
32                  5 591       4   3.71
34                  1 327*      4   4.73
36                  9 551       4   3.93
38                 30 593       5   3.68
40                 19 333*      5   4.05
42                 16 141*      5   4.33
44                 15 683*      5   4.55
46                 81 463       5   4.07
48                 28 229*      5   4.68
50                 31 907       5   4.82
52                 19 609*      5   5.26
54                 35 617       5   5.15
56                 82 073       5   4.95
58                 44 293*      5   5.42
60                 43 331*      5   5.62
62                 34 061*      5   5.94
64                 89 689       5   5.61
66                162 143       6   5.50
68                134 513*      6   5.76
70                173 359       6   5.80
72                 31 397*      5   6.95
74                404 597       6   5.73
76                212 701*      6   6.20
78                188 029*      6   6.42
80                542 603       6   6.06
82                265 621*      6   6.57
84                461 717       6   6.44
86                155 921*      6   7.19
88                544 279       6   6.66
90                404 851*      6   6.97
92                927 869       6   6.70
94              1 100 977       7   6.76
96                360 653*      6   7.50
98                604 073       6   7.36
100               396 733*      6   7.76
102             1 444 309       7   7.19
104             1 388 483*      7   7.35
106             1 098 847*      7   7.62
108             2 238 823       7   7.39
110             1 468 277*      7   7.75
112               370 261*      6   8.74
114               492 113       6   8.70
116             5 845 193       7   7.44
118             1 349 533*      7   8.36
120             1 895 359       7   8.30
122             3 117 299       7   8.16
124             6 752 623       7   7.89
126             1 671 781*      7   8.79
128             3 851 459       7   8.44
130             5 518 687       7   8.37
132             1 357 201*      7   9.35
134             6 958 667       7   8.50
136             6 371 401*      7   8.68
138             3 826 019*      7   9.10
140             7 621 259       7   8.83
142            10 343 761       8   8.79
144            11 981 443       8   8.83
146             6 034 247*      7   9.35
148             2 010 733*      7  10.20
150            13 626 257       8   9.13
152             8 421 251*      7   9.53
154             4 652 353*      7  10.03
156            17 983 717       8   9.34
158            49 269 581       8   8.92
160            33 803 689*      8   9.23
162            39 175 217       8   9.27
164            20 285 099*      8   9.75
166            83 751 121       8   9.10
168            37 305 713*      8   9.64
170            27 915 737*      8   9.92
172            38 394 127       8   9.85
174            52 721 113       8   9.79
176            38 089 277*      8  10.08
178            39 389 989       8  10.18
180            17 051 707*      8  10.81
182            36 271 601       8  10.46
184            79 167 733       8  10.12
186           147 684 137       9   9.89
188           134 065 829*      9  10.05
190           142 414 669       9  10.12
192           123 454 691*      9  10.31
194           166 726 367       9  10.25
196            70 396 393*      8  10.85
198            46 006 769*      8  11.22
200           378 043 979       9  10.13
202           107 534 587*      9  10.92
204           112 098 817       9  11.01
206           232 423 823       9  10.69
208           192 983 851*      9  10.90
210            20 831 323*      8  12.46
212           215 949 407       9  11.05
214           253 878 403       9  11.06
216           202 551 667*      9  11.29
218           327 966 101       9  11.12
220            47 326 693*      8  12.45
222           122 164 747       9  11.92
224           409 866 323       9  11.30
226           519 653 371       9  11.26
228           895 858 039       9  11.06
230           607 010 093*      9  11.37
232           525 436 489*      9  11.55
234           189 695 659*      9  12.28
236           216 668 603       9  12.30
238           673 919 143       9  11.71
240           391 995 431*      9  12.13
242           367 876 529*      9  12.27
244           693 103 639       9  11.99
246           555 142 061*      9  12.22
248           191 912 783*      9  13.00
250           387 096 133       9  12.64
252           630 045 137       9  12.44
254         1 202 442 089      10  12.15
256         1 872 851 947      10  11.99
258         1 316 355 323*     10  12.29
260           944 192 807*      9  12.58
262         1 649 328 997      10  12.34
264         2 357 881 993~     10  12.23
266         1 438 779 821*     10  12.61
268         1 579 306 789      10  12.65
270         1 391 048 047*     10  12.82
272         1 851 255 191      10  12.75
274         1 282 463 269*     10  13.07
276           649 580 171*      9  13.60
278         4 260 928 601~     10  12.54
280         1 855 047 163*     10  13.12
282           436 273 009*      9  14.18
284         1 667 186 459      10  13.37
286         2 842 739 311~     10  13.14
288         1 294 268 491*     10  13.73
290         1 948 819 133      10  13.56
292         1 453 168 141*     10  13.84
294         5 692 630 189~     10  13.09
296         5 260 030 511*     10  13.22
298         8 650 524 583~     10  13.02
300         4 758 958 741*     10  13.46
302         6 675 573 497~     10  13.35
304         2 433 630 109*     10  14.07
306         3 917 587 237~     10  13.85
308         5 490 459 101~     10  13.73
310         4 024 713 661*     10  14.02
312         6 570 018 347~     10  13.80
314         8 948 418 749~     10  13.70
316        19 048 111 843~     11  13.35
318         4 372 999 721*     10  14.33
320         2 300 942 549*     10  14.84
322         7 961 074 441~     10  14.12
324        15 204 335 189~     11  13.82
326         5 837 935 373*     10  14.50
328        13 086 861 181~     11  14.08
330         6 291 356 009*     10  14.63
332         5 893 180 121*     10  14.76
334        30 827 138 509~     11  13.83
336         3 842 610 773*     10  15.22
338        22 076 314 313~     11  14.19
340         8 605 261 447*     10  14.86
342        12 010 745 569~     11  14.74
344        19 724 087 267~     11  14.51
346        11 291 401 837*     11  14.95
348        17 002 876 643~     11  14.77
350        22 625 830 967~     11  14.68
352        30 750 892 801~     11  14.58
354         4 302 407 359*     10  15.96
356        30 553 665 323~     11  14.75
358        16 792 321 339*     11  15.21
360        32 331 294 913~     11  14.88
362        35 877 724 601~     11  14.90
364        25 425 617 317*     11  15.19
366        20 108 776 097*     11  15.43
368        57 133 794 521~     11  14.86
370        59 942 358 571~     11  14.91
372        20 404 137 779*     11  15.67
374        23 064 761 663~     11  15.67
376        16 161 669 787*     11  16.00
378        38 116 957 819~     11  15.51
380        23 323 808 741*     11  15.92
382        10 726 904 659*     11  16.54
384        20 678 048 297~     11  16.17
386        35 238 645 587~     11  15.89
388       156 798 792 223~     12  15.05
390        53 241 805 651*     11  15.79
392       117 215 204 531~     12  15.38
394        22 367 084 959*     11  16.53
396        50 806 025 873~     11  16.06
398        40 267 027 589*     11  16.30
400        47 203 303 159~     11  16.27
402        44 293 346 177*     11  16.40
404       144 999 022 043~     12  15.72
406        49 306 638 307*     11  16.49
408       134 664 608 389~     12  15.92
410        98 276 144 093*     11  16.20
412       124 221 464 119~     12  16.13
414        49 914 935 177*     11  16.81
416       121 972 158 437~     12  16.30
418       129 300 694 603~     12  16.34
420        82 490 815 123*     11  16.71
422       280 974 865 361~     12  16.01
424       264 495 345 259*     12  16.12
426       180 265 084 403*     12  16.44
428       219 950 168 411~     12  16.39
430       250 964 194 171~     12  16.38
432        87 241 770 619*     11  17.15
434       127 084 569 923~     12  16.97
436       367 459 059 871~     12  16.37
438       101 328 529 441*     12  17.28
440       141 846 299 801~     12  17.14
442       417 470 554 687~     12  16.52
444        36 172 730 063*     11  18.26
446       190 418 076 203~     12  17.17
448       402 872 474 743~     12  16.77
450        63 816 175 447*     11  18.09
452       466 855 187 471~     12  16.82
454       202 530 831 163*     12  17.44
456        25 056 082 087*     11  19.04
458       304 040 251 469~     12  17.32
460       131 956 235 563*     12  17.96
462       400 729 567 081~     12  17.29
464        42 652 618 343*     11  18.96
466       565 855 695 631~     12  17.22
468       127 976 334 671*     12  18.30
470       681 753 256 133~     12  17.25
472       865 244 709 607~     12  17.17
474       182 226 896 239*     12  18.28
476       725 978 934 347~     12  17.43
478       367 766 547 571*     12  17.95
480       482 423 533 897~     12  17.84
482     1 051 602 787 181~     13  17.41
484       767 644 374 817*     12  17.69
486       241 160 624 143*     12  18.54
488     1 275 363 152 099~     13  17.51
490       297 501 075 799*     12  18.55
492       910 361 180 689~     12  17.87
494       804 541 404 419*     12  18.02
496       880 318 998 907~     12  18.03
498       428 315 806 823*     12  18.59
500       303 371 455 241*     12  18.91
502     1 258 535 916 601~     13  18.02
504       747 431 049 203*     12  18.43
506     1 339 347 750 707~     13  18.12
508     1 841 086 484 491~     13  17.99
510     2 209 016 910 131~     13  17.94
512     1 999 066 711 391*     13  18.08
514       304 599 508 537*     12  19.44
516       416 608 695 821~     12  19.29
518     2 296 497 058 133~     13  18.20
520     2 336 167 262 449~     13  18.26
522     1 214 820 695 701*     13  18.76
524     2 256 065 636 039~     13  18.42
526     1 620 505 682 371*     13  18.71
528     1 529 741 785 139*     13  18.82
530     2 205 492 372 371~     13  18.65
532       461 690 510 011*     12  19.81
534       614 487 453 523~     12  19.67
536     7 345 405 044 851~     13  18.09
538     2 122 536 905 311*     13  18.95
540       738 832 927 927*     12  19.76
542     6 977 007 335 399~     13  18.33
544     2 863 348 756 453*     13  18.97
546     2 164 206 784 721*     13  19.22
548     5 229 310 142 321~     13  18.71
550     2 496 646 209 271*     13  19.27
552     2 210 401 546 601*     13  19.42
554     7 345 765 475 309~     13  18.70
556    13 962 609 095 641~     14  18.37
558     5 263 973 982 823*     13  19.05
560     4 260 199 366 373*     13  19.26
562     2 081 209 441 279*     13  19.81
564     1 480 064 231 153*     13  20.13
566     4 897 642 179 197~     13  19.37
568     8 073 723 367 159~     13  19.11
570     4 442 109 925 217*     13  19.57
572    10 632 240 465 101~     14  19.07
574    15 500 918 618 077~     14  18.90
576     9 279 720 833 771*     13  19.29
578     8 820 339 715 823*     13  19.39
580     9 383 081 340 541~     13  19.42
582     1 346 294 310 749*     13  20.84
584     6 993 007 248 239~     13  19.75
586     6 769 976 169 673*     13  19.84
588     1 408 695 493 609*     13  21.02
590    20 761 252 261 751~     14  19.24
592    13 713 880 081 861*     14  19.57
594     5 499 789 519 863*     13  20.25
596    14 492 574 443 261~     14  19.67
598     5 614 481 773 561*     13  20.37
600     4 872 634 110 067*     13  20.54
602     1 968 188 556 461*     13  21.27
604     5 439 564 948 583~     13  20.60
606    12 112 937 821 403~     14  20.12
608    25 122 615 811 883~     14  19.71
610     9 105 981 382 177*     13  20.44
612    13 397 310 636 587~     14  20.25
614    17 418 754 709 747~     14  20.14
616    17 272 372 767 523*     14  20.21
618     4 165 633 395 149*     13  21.27
620    18 100 944 245 837~     14  20.31
622    13 059 969 946 711*     14  20.60
624    24 923 033 918 059~     14  20.23
626    33 605 480 400 197~     14  20.10
628    34 140 047 613 391~     14  20.15
630    12 644 461 143 649*     14  20.88
632    45 678 685 880 759~     14  20.09
634    17 659 394 869 309*     14  20.79
636     9 483 480 841 753*     13  21.28
638    17 499 522 060 011~     14  20.92
640    22 099 408 494 481~     14  20.83
642    14 141 685 364 577*     14  21.20
644    41 433 781 612 373~     14  20.54
646    51 027 160 468 351~     14  20.47
648     9 787 731 507 761*     13  21.66
650     5 120 731 250 207*     13  22.21
652     2 614 941 710 599*     13  22.80
654    54 916 086 007 427~     14  20.67
656    65 862 966 031 241~     14  20.62
658    39 883 132 551 139*     14  21.01
660    10 653 514 291 843*     14  22.00
662    11 082 394 066 097~     14  22.04
664    38 745 678 640 849~     14  21.22
666    18 691 113 008 663*     14  21.79
668    28 340 177 964 929~     14  21.57
670    47 137 733 785 861~     14  21.28
672    26 456 514 142 099*     14  21.74
674     7 177 162 611 713*     13  22.77
676    78 610 833 115 261~     14  21.13
678    37 970 994 487 033*     14  21.68
680    82 385 435 331 119~     14  21.22
682    41 459 443 375 351*     14  21.75
684    30 236 507 704 253*     14  22.04
686    74 014 757 794 301~     14  21.48
688   110 526 670 235 599~     15  21.28
690    15 712 145 060 693*     14  22.71
692    43 603 583 701 331~     14  22.03
694    62 088 893 223 739~     14  21.85
696    23 333 096 984 797*     14  22.61
698    33 785 727 371 453~     14  22.41
700    14 998 144 209 049*     14  23.07
702    19 786 638 118 631~     14  22.93
704    97 731 545 943 599~     14  21.85
706    35 625 755 878 981*     14  22.63
708   143 679 495 784 681~     15  21.72
710   138 965 383 978 937*     15  21.80
712   106 749 746 034 601*     15  22.04
714    49 639 993 268 989*     14  22.64
716    13 829 048 559 701*     14  23.66
718    82 342 388 119 111~     14  22.41
720   111 113 196 467 011~     15  22.26
722   218 356 872 845 927~     15  21.87
724    59 692 452 738 913*     14  22.82
726   156 100 489 308 167~     15  22.21
728    57 723 522 921 803*     14  22.97
730    24 179 270 588 173*     14  23.69
732   140 085 225 001 801~     15  22.47
734   154 312 610 974 979~     15  22.47
736   161 443 383 249 583~     15  22.50
738   143 282 994 823 909*     15  22.64
740    57 360 609 786 539*     14  23.36
742   189 442 329 715 069~     15  22.57
744    42 610 475 373 269*     14  23.71
746   184 219 698 008 123~     15  22.71
748   172 373 989 611 793*     15  22.82
750   145 508 250 945 419*     15  23.00
752                             0    NaN
754   219 831 875 554 399~     15  22.83
756    70 099 348 325 843*     14  23.71
758    47 581 758 352 253*     14  24.07
760    98 103 148 488 133~     14  23.59
762   144 895 907 074 481~     15  23.37
764                             0    NaN
766    19 581 334 192 423*     14  25.03
768                             0    NaN
770   214 198 375 528 463~     15  23.33
772   186 129 514 280 467*     15  23.50
774                             0    NaN
776   187 865 909 338 091~     15  23.61
778    42 842 283 925 351*     14  24.79
780                             0    NaN
782                             0    NaN
784                             0    NaN
786                             0    NaN
788    96 949 415 903 999~     14  24.47
790                             0    NaN
792                             0    NaN
794                             0    NaN
796                             0    NaN
798                             0    NaN
800                             0    NaN
802                             0    NaN
804    90 874 329 411 493*     14  25.02
806   171 231 342 420 521~     15  24.59
808                             0    NaN
810                             0    NaN
812                             0    NaN
814                             0    NaN
816                             0    NaN
818                             0    NaN
820                             0    NaN
822                             0    NaN
824                             0    NaN
826                             0    NaN
828                             0    NaN
830                             0    NaN
832                             0    NaN
834                             0    NaN
836                             0    NaN
838                             0    NaN
840                             0    NaN
842                             0    NaN
844                             0    NaN
846                             0    NaN
848                             0    NaN
850                             0    NaN
852                             0    NaN
854                             0    NaN
856                             0    NaN
858                             0    NaN
860                             0    NaN
862                             0    NaN
864                             0    NaN
866                             0    NaN
868                             0    NaN
870                             0    NaN
872                             0    NaN
874                             0    NaN
876                             0    NaN
878                             0    NaN
880                             0    NaN
882                             0    NaN
884                             0    NaN
886                             0    NaN
888                             0    NaN
890                             0    NaN
892                             0    NaN
894                             0    NaN
896                             0    NaN
898                             0    NaN
900                             0    NaN
902                             0    NaN
904                             0    NaN
906   218 209 405 436 543~     15  27.44
----------------------------------------
```

## Longest run
Nicely's result [from 1998](https://pzktupel.de/RecordGaps/GAP01FO.php) obtained in 1w 5d 12h 43m 23s. 
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
SieveGap/DoubleBuffer/ParallelPrimes/FastFoward: Searching gap >= 906 found.
>> 916
 + 1 189 459 969 825 483
 = 1 189 459 969 826 399
904291.081s (tot=1w 5d 12h 43m 23s), 51 bits, 16 digits, x5.5 prev, 26.39 merit, ~1.18946e+15, 32 621 231 p/s.
```

# Records
Up-to-date record list: [here](https://www.pzktupel.de/RecordGaps/risinggap.php).
