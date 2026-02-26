package primegap;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import primegap.util.IncreasingBigIntegers;

public class SieveGap extends NaiveGap {

	/**
	 * Windowed Sieve of Eratosthenes optimized for finding large primes starting
	 * from an arbitrary BigInteger position.
	 * <p>
	 * This implementation uses a bit-packed sliding window where each bit
	 * represents an odd number only (even numbers are skipped). Bit k represents
	 * the number: start + 2*k. Each long (64 bits) covers a range of 128
	 * consecutive numbers (64 odd + 64 even skipped).
	 * </p>
	 * <p>
	 * The sieve works incrementally: as primes are discovered, they are immediately
	 * used to mark their multiples as composite in the current and future windows.
	 * </p>
	 */
	class SlidingWindowSieve implements Supplier<BigInteger> {
		boolean useBulk = false; // true  --> gap=464 in 447sec  false --> 434
		// parallel true --> 413  / false -> 397
		
		/** Collection of all discovered primes, maintained in sorted order */
		final IncreasingBigIntegers primes;

		/** Bit-packed array representing odd prime candidates in the current window */
		final int tabLen;
		long[] tab;

		/** Number of odd candidates represented (= tab.length * 64) */
		final int windowSize;
		final BigInteger windowSize_;

		/** Real range covered by the window (= tab.length * 128) */
		final int windowRange;
		final BigInteger windowRange_;

		static final BigInteger ZERO = i(0);
		static final BigInteger ONE = i(1);
		static final BigInteger TWO = i(2);
		static final BigInteger THREE = i(3);

		/**
		 * Convenience method to create BigInteger from int.
		 * 
		 * @param v the integer value
		 * @return BigInteger representation of v
		 */
		static BigInteger i(int v) {
			return BigInteger.valueOf(v & 0xFFFFFFFFL);
		}

		/** First odd number in the current window */
		protected BigInteger start;

		/* sqrt(start+windowRange) */
		protected BigInteger limit;

		/** last returned prime */
		protected BigInteger lastPrime = TWO;

		/** do we need to continue marking next primes */
		protected boolean doMarking = true;

		/**
		 * Constructs a new windowed sieve.
		 * 
		 * @param size   the size of the bit array (in longs). Window covers size*128
		 *               consecutive numbers (size*64 odd numbers). For optimal
		 *               alignment, size should be chosen such that size*128 is a
		 *               convenient range (e.g., powers of 2).
		 * @param primes the shared TreeSet to store discovered primes
		 */
		public SlidingWindowSieve(int size) {
			size = Math.min(size, Integer.MAX_VALUE / 128); // safety

			this.primes = new IncreasingBigIntegers(1 << 24, 896779142); // 16Mb

			this.windowSize = size * 64; // Number of bits/odd numbers
			this.windowRange = size * 128; // Actual consecutive numbers covered
			this.windowSize_ = v(this.windowSize);
			this.windowRange_ = v(this.windowRange);
			this.tab = newTab(this.tabLen = size);
			setStart(THREE);
			pending = IntStream.of(0).iterator();
		}

		protected long[] newTab(int size) {
			return new long[size];
		}

		protected void fillTab(long[] tab, long val) {
			Arrays.fill(tab, val);
		}

		protected void updateTab(long[] tab, int i, long mask) {
			tab[i] |= mask;
//			String s = Long.toBinaryString(tab[i]);
		}

		protected long getTab(long[] tab, int i) {
			return tab[i];
		}

		public long numPrimes() {
			return primes.sizeLong();
		}

		public BigInteger getLastPrime() {
			return lastPrime;
		}

		/**
		 * Positions the sieve window starting at the given number and initializes the
		 * bit array by sieving with all known primes up to sqrt(window_end).
		 * 
		 * @param start the starting position (will be adjusted to next odd if even)
		 */
		public void setStart(BigInteger start) {
			// Ensure start is odd
			if (start.testBit(0)) {
				this.start = start;
			} else {
				this.start = start.add(ONE);
			}

			String s = String.format(Locale.ENGLISH, "start=%s %.1f%% ~%.1f", this.start,
					(numPrimes() * 100.0) / primes.limit(), getLastPrime().doubleValue() / primeCallCount);
			System.err.print(s + "\b".repeat(s.length()));

			// Sieve limit: sqrt(start + windowRange)
			limit = this.start.add(windowRange_).sqrt();
			if (primes.isFull())
				if (primes.getLast().compareTo(limit) < 0)
					throw new RuntimeException(
							"Primes size limit is too short (" + primes.sizeLong() + "). Please increase!");
			markAllMultiples();

			last_tab = (mask = -1) ^ getTab(tab, last = 0);

			doMarking = true;
		}
		
		protected void markMultiplesOf(BigInteger P)  {
			markMultiplesOf(start, tab, P);
		}

		protected void markAllMultiples() {
			fillTab(tab, 0);
			long now = timer.getAsLong();
			primes.stream().takeWhile(p -> p.compareTo(limit) <= 0).forEach(this::markMultiplesOf);
			dbg("all=", (timer.getAsLong() - now) / 1e6, "ms              ");
		}

		/**
		 * Clears the bit for each odd multiple of p within the current window. Uses bit
		 * accumulation to minimize memory accesses (one write per long).
		 * <p>
		 * Since only odd numbers are represented, bit k corresponds to number start +
		 * 2*k. For an odd prime p, consecutive odd multiples are spaced by 2*p in
		 * number space, which corresponds to spacing p in bit position space.
		 * </p>
		 * 
		 * @param p an odd prime number
		 */
		protected void markMultiplesOf(BigInteger start, long tab[], BigInteger p) {
			// Find offset to first multiple of p >= start
			BigInteger n = start.remainder(p);
			if (n.signum() > 0)
				n = p.subtract(n);
			// n = 0..p-1

			// If n is even, advance to next odd multiple: n += p
			// (since p is odd, p + even = odd)
			if (n.testBit(0))
				n = n.add(p);

			// Skip if beyond range
			if (n.compareTo(windowRange_) >= 0)
				return;

			
			// Convert to bit position
			int bitPos = (n.intValue() >>> 1);
			int i = bitPos >>> 6; // Index in tab array
			int b = bitPos & 63; // Bit position within long

			// Accumulate bits to clear
			long mask = (1L << b);

			// Check if p is small enough to have multiple occurrences
			// that is p < windowRange/2 = windowSize
			if (p.compareTo(windowSize_) < 0) {
				// Small prime: multiple odd multiples in window
				long pLong = p.longValue();
				long pos = bitPos + pLong; // Use long here to avoid overflow in loop

				// first occurrence appear before the first half
				while (pos < windowSize) {
					int nextI = (int) (pos >>> 6);
					int nextB = (int) (pos & 63);

					// Flush mask when moving to different long
					if (nextI != i) {
						updateTab(tab, i, mask);
						i = nextI;
						mask = 0;
					}

					mask |= (1L << nextB);
					pos += pLong;
				}
			}

			// Apply final mask
			updateTab(tab, i, mask);
		}
		
		/**
		 * Finds the next set bit in the window, representing the next prime candidate.
		 * 
		 * @return bit position of next candidate, or -1 if window is exhausted
		 */
		@SuppressWarnings("unused")
		protected int next() {
			if (last_shift == 0) {
				long v = last_tab & mask;

				while (v == 0) {
					if (++last == tabLen)
						return -1;
					last_tab = v = ~getTab(tab, last);
				}
				int i = Long.numberOfTrailingZeros(v);
				mask = -2L << i;
				return (last << 6) + i;
			} else {
				long v = last_tab & mask;

				while (v == 0) {
					int i = (last += 64) >>> last_shift;
					if (i == tabLen)
						return -1;
					last_tab = v = ~getTab(tab, i);
				}
				int i = Long.numberOfTrailingZeros(v);
				mask = -2L << i;
				return last + i;
			}
		}

		final int last_shift = 6;
		protected int last;
		protected long mask;
		protected long last_tab;

		protected static PrimitiveIterator.OfInt EMPTY = new PrimitiveIterator.OfInt() {
			public boolean hasNext() {
				return false;
			}

			public int nextInt() {
				throw new NoSuchElementException();
			}
		};
		PrimitiveIterator.OfInt pending = EMPTY;

		protected IntStream bulkExpand(int i) {
			long v = ~tab[i];
			if (v == (v & -v)) // 0 or 1 bit
				return v == 0 ? IntStream.empty() : IntStream.of((i << 6) + Long.numberOfTrailingZeros(v));
			int[] tmp = new int[Long.bitCount(v)];
			int n = 0;
			while (v != 0L) {
				tmp[n++] = (i << 6) + Long.numberOfTrailingZeros(v);
				v &= v - 1L;
			}
			return Arrays.stream(tmp);
		}

		protected IntStream bulk(int from) {
//			System.err.println("Bulk " + from + "->" + tabLen + " (" + start + ")");
			return IntStream.range(from, tabLen).flatMap(this::bulkExpand);
		}

		long bulk_start;

		/**
		 * Returns the next prime number. Implements Supplier&lt;BigInteger&gt;.
		 * <p>
		 * If the current window is exhausted, slides the window forward by windowRange
		 * and continues. Each discovered prime is added to the shared primes collection
		 * and immediately used to mark its multiples in the current window.
		 * </p>
		 * 
		 * @return the next prime number
		 */
		@Override
		public BigInteger get() {
			int k;
			if (pending.hasNext()) {
				k = pending.nextInt();
			} else {
				while ((k = next()) < 0) {
					long now = timer.getAsLong();
					if (pending != EMPTY) {
						pending = EMPTY;
						dbg("Bulk done (", (now - bulk_start) / 1e9, "s)                                    ");
					}
					slideWindow();
					dbg("Slide done (", (timer.getAsLong() - now) / 1e9,
							"s)                                                ");
				}
			}

			// Convert bit position to actual odd number: start + 2*k
			BigInteger prime = start.add(i(k << 1));
			primes.add(prime);

			if (doMarking) {
				if (prime.compareTo(limit) <= 0) {
					long now = timer.getAsLong();
					markMultiplesOf(start, tab, prime);
					last_tab = ~getTab(tab, last >>> last_shift);
					dbg("Marking multiples of ", prime, " in ", (timer.getAsLong() - now) / 1e6, "ms.");
				} else {
					doMarking = false;
				}
				if (useBulk && pending == EMPTY && (last_tab & mask) == 0L) {
					bulk_start = timer.getAsLong();
					pending = bulk((last >>> last_shift) + 1).iterator();
					dbg("Bulk started (", (timer.getAsLong() - bulk_start) / 1e9, "s)");
				}
			}

			++primeCallCount;

			return lastPrime = prime;
		}

		// Slide window by windowRange (128 * tab.length consecutive numbers)
		protected void slideWindow() {
			setStart(start.add(windowRange_));
		}
	}

	SlidingWindowSieve supplier = newSlidingWindowSieve(10_240_000);

	SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new SlidingWindowSieve(size);
	}

	@Override
	protected BigInteger nextPrimeImpl(BigInteger N) {
		BigInteger Q = supplier.getLastPrime();
		while (Q == null || Q.compareTo(N) <= 0) {
			Q = supplier.get();
		}
		return Q;
	}

	public static void main(String[] args) {
		new SieveGap().run();
	}
}
