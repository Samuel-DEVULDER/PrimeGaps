package attic;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

import primegap.naive.NaiveGap;
import primegap.util.IncreasingBigIntegers;

public class SieveGap2 extends NaiveGap {

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
	static class SlidingWindowSieve2 implements Supplier<BigInteger> {
		/** Collection of all discovered primes, maintained in sorted order */
		final IncreasingBigIntegers primes;

		/** Bit-packed array representing odd prime candidates in the current window */
		final long[] tab;
		final int tabLen;

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
		BigInteger start;
		
		/* sqrt(start+windowRange) */
		BigInteger limit;

		/** Bit position of the last returned prime candidate (for iteration) */
		int last;

		/**
		 * Constructs a new windowed sieve.
		 * 
		 * @param size   the size of the bit array (in longs). Window covers size*128
		 *               consecutive numbers (size*64 odd numbers). For optimal
		 *               alignment, size should be chosen such that size*128 is a
		 *               convenient range (e.g., powers of 2).
		 * @param primes the shared TreeSet to store discovered primes
		 */
		public SlidingWindowSieve2(int size) {
			size = Math.min(size, Integer.MAX_VALUE / 128); // safety

			this.primes = new IncreasingBigIntegers(1 << 24); // 16Mb

			this.windowSize = size * 64; // Number of bits/odd numbers
			this.windowRange = size * 128; // Actual consecutive numbers covered
			this.windowSize_ = v(this.windowSize);
			this.windowRange_ = v(this.windowRange);
			this.tab = newTab(this.tabLen = size);
		}

		long[] newTab(int size) {
			return new long[size];
		}

		void fillTab(long val) {
			Arrays.fill(tab, val);
		}

		void updateTab(int i, long mask) {
			tab[i] &= ~mask;
//			String s = Long.toBinaryString(tab[i]);
		}

		long getTab(int i) {
			return tab[i];
		}

		public long numPrimes() {
			return primes.sizeLong();
		}

		public BigInteger getLastPrime() {
			return Optional.ofNullable(primes.getLast()).orElse(ZERO);
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

			double avg_gap = Math.rint(10 * getLastPrime().doubleValue() / numPrimes()) / 10.0;

			String s = "start = " + this.start + " #primes=" + numPrimes() + " gap=~" + avg_gap;
			System.err.print(s + "\b".repeat(s.length()));

			fillTab(-1L);

			// Sieve limit: sqrt(start + windowRange)
			limit = this.start.add(windowRange_).sqrt();
			markAllMultiples();

			last = 0;
		}

		void markAllMultiples() {
			primes.stream().takeWhile(p -> p.compareTo(limit) <= 0).forEach(this::markMultiplesOf);
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
		void markMultiplesOf(BigInteger p) {
			// Find offset to first multiple of p >= start
			BigInteger n = start.remainder(p);
			if (!n.equals(ZERO))
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
						updateTab(i, mask);
						i = nextI;
						mask = 0;
					}

					mask |= (1L << nextB);
					pos += pLong;
				}
			}

			// Apply final mask
			updateTab(i, mask);
		}

		/**
		 * Finds the next set bit in the window, repres produit terme à terme des partiesenting the next prime candidate.
		 * 
		 * @return bit position of next candidate, or -1 if window is exhausted
		 */
		int next() {
			int i = ++last >>> 6;
			if (i == tabLen)
				return -1;
			long v = getTab(i) & (-1L << (last & 63));

			while (v == 0) {
				if (++i == tabLen)
					return -1;
				v = getTab(i);
			}
			last = (i << 6) + Long.numberOfTrailingZeros(v);
			return last;
		}

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
			BigInteger prime = THREE;
			if (primes.isEmpty()) {
				setStart(prime);
			} else {
				int k;
				while ((k = next()) < 0) {
					// Slide window by windowRange (128 * tab.length consecutive numbers)
					setStart(start.add(windowRange_));
				}
				// Convert bit position to actual odd number: start + 2*k
				prime = start.add(i(k << 1));
			}

			primes.add(prime);

			if (prime.compareTo(limit) <= 0)
				markMultiplesOf(prime);

			return prime;
		}
	}

	SlidingWindowSieve2 supplier = newSlidingWindowSieve(10_240_000);

	SlidingWindowSieve2 newSlidingWindowSieve(int size) {
		return new SlidingWindowSieve2(size);
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
		new SieveGap2().run();
	}
}
