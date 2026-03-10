package primegap.sieve;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import primegap.naive.NaiveGap;
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
	static public class SlidingWindowSieve implements Supplier<BigInteger> {
		final public SieveGap sieve;

		/** Collection of all discovered primes, maintained in sorted order */
		public final IncreasingBigIntegers primes;

		/** Bit-packed array representing odd prime candidates in the current window */
		public final int tabLen;
		private long[] tab;

		/** Number of odd candidates represented (= tab.length * 64) */
		protected final int windowSize;
		final BigInteger windowSize_;

		/** Real range covered by the window (= tab.length * 128) */
		protected final long windowRange;
		protected final BigInteger windowRange_;

		static final BigInteger ZERO = v(0);
		static final BigInteger ONE = v(1);
		static final BigInteger TWO = v(2);
		static final BigInteger THREE = v(3);

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
		public SlidingWindowSieve(SieveGap sieve, int size) {
			this(sieve, adaptSize(size), adaptRange(adaptSize(size) * 64));
			bootstrap();
		}

		protected SlidingWindowSieve(SieveGap sieve, SlidingWindowSieve delegate) {
			this.sieve = delegate.sieve;
			this.primes = delegate.primes;
			this.setTab(delegate.getTab());
			this.tabLen = delegate.tabLen;
			this.windowSize = delegate.windowSize;
			this.windowRange = delegate.windowRange;
			this.windowRange_ = delegate.windowRange_;
			this.windowSize_ = delegate.windowSize_;
			this.start = delegate.start;
			this.limit = delegate.limit;
			this.pending  =  delegate.pending;
		}

		protected SlidingWindowSieve(SieveGap sieve, int size, long range) {
			this.sieve = sieve;

			this.primes = new IncreasingBigIntegers(1 << 24, 896779142 / 2 / 2 / 2); // 16Mb

			this.windowSize = size * 64; // Number of bits/odd numbers
			this.windowRange = range; // Actual consecutive numbers covered
			this.windowSize_ = v(this.windowSize);
			this.windowRange_ = v(this.windowRange);
			this.setTab(newTab(this.tabLen = size));
		}

		// -------------------------------------------------------------------
		// Wheel hooks - override these in subclasses
		// -------------------------------------------------------------------

		/**
		 * Adjusts the requested tabLen for safety and wheel alignment. Default
		 * (wheel2): clamp so that windowRange = tabLen*128 fits in a long.
		 */
		static protected int adaptSize(int size) {
			return Math.min(size, Integer.MAX_VALUE / 64); // windowSize=size*64 must fit int
		}

		/**
		 * Computes the real numeric range from the number of candidate bits. Default
		 * (wheel2): 1 bit = 2 integers.
		 */
		static protected long adaptRange(int totalBits) {
			return totalBits * 2L;
		}

		/**
		 * Converts bit index k to numeric offset from start. Default (wheel2): bit k ->
		 * 2k.
		 */
		protected long bitposToNum(int bitpos) {
			long l = bitpos & 0xFFFFFFFFL;
			return l << 1;
		}

		/**
		 * Seeds the sieve: sets window start and initialises the pending iterator.
		 * Default (wheel2): start at 3, bootstrap via IntStream.of(0).
		 */
		protected void bootstrap() {
			setStart(THREE);
			pending = IntStream.of(-3).iterator();
		}

		// -------------------------------------------------------------------
		// Core internals
		// -------------------------------------------------------------------

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
		// Ensure start is odd
		public void setStart(BigInteger start) {
			// if (start.testBit(0)) {
			this.start = start;
			// } else {
			// this.start = start.add(ONE);
			// }

			String s = String.format(Locale.ENGLISH, "start=%s %.1f%% ~%.1f", this.getStart(),
					(numPrimes() * 100.0) / primes.limit(), getLastPrime().doubleValue() / sieve.primeCallCount);
			System.err.print(s + "\b".repeat(s.length()));

			// Sieve limit: sqrt(start + windowRange)
			limit = this.getStart().add(windowRange_).sqrt();
			if (primes.isFull())
				if (primes.getLast().compareTo(limit) < 0)
					throw new RuntimeException(
							"Primes size limit is too short (" + primes.sizeLong() + "). Please increase!");
			markAllMultiples();

			last_tab = (mask = -1) ^ getTab(getTab(), last = 0);

			doMarking = true;
		}

		protected void markMultiplesOf(BigInteger P) {
			markMultiplesOf(getStart(), getTab(), P);
		}

		protected void markAllMultiples() {
			fillTab(getTab(), 0);
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

				while (v == 0L) {
					if (++last == tabLen)
						return -1;
					last_tab = v = ~getTab(getTab(), last);
				}
				int i = Long.numberOfTrailingZeros(v);
				mask = -2L << i;
				return (last << 6) + i;
			} else {
				long v = last_tab & mask;

				while (v == 0L) {
					int i = (last += 64) >>> last_shift;
					if (i == tabLen)
						return -1;
					last_tab = v = ~getTab(getTab(), i);
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
		public PrimitiveIterator.OfInt pending = EMPTY;

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

			++sieve.primeCallCount;

			if (pending.hasNext()) {
				k = pending.nextInt();
				if (k < 0)
					return lastPrime = v(-k);
			} else {
				while ((k = next()) < 0) {
					long now = timer.getAsLong();
					if (pending != EMPTY) {
						pending = EMPTY;
					}
					slideWindow();
					dbg("Slide done (", (timer.getAsLong() - now) / 1e9,
							"s)                                                ");
				}
			}

			// Convert bit position to actual odd number: start + 2*k
			BigInteger prime = getStart().add(v(bitposToNum(k)));
			// System.err.println("Candidate bit=" + k + ", num=" + bitposToNum(k) + ",
			// prime=" + prime + " (rem="
			// + prime.mod(v(30)) + ")");
			primes.add(prime);

			if (doMarking) {
				if (prime.compareTo(limit) <= 0) {
					long now = timer.getAsLong();
					markMultiplesOf(getStart(), getTab(), prime);
					last_tab = ~getTab(getTab(), last >>> last_shift);
					dbg("Marking multiples of ", prime, " in ", (timer.getAsLong() - now) / 1e6, "ms.");
				} else {
					doMarking = false;
					dbg("disabled marking for ", getStart());
				}
			}

			return lastPrime = prime;
		}

		// Slide window by windowRange (128 * tab.length consecutive numbers)
		protected void slideWindow() {
			setStart(getStart().add(windowRange_));
		}

		protected String name() {
			return this.getClass().getSimpleName();
		}

		public BigInteger getStart() {
			return start;
		}

		public long[] getTab() {
			return tab;
		}

		public void setTab(long[] tab) {
			this.tab = tab;
		}
	}

	static public class DelegatingSlidingWindowSieve extends SlidingWindowSieve {
		protected final SlidingWindowSieve delegate;

		public DelegatingSlidingWindowSieve(SieveGap sieve, SlidingWindowSieve delegate) {
			super(sieve, delegate);
			this.delegate = delegate;
		}

		@Override
		protected String name() {
			return delegate.name();
		}

		@Override
		protected long bitposToNum(int bitpos) {
			return delegate.bitposToNum(bitpos);
		}

		@Override
		protected void bootstrap() {
			throw new UnsupportedOperationException("DelegatingSlidingWindowSieve does not support bootstrap()");
		}

		@Override
		protected void markMultiplesOf(BigInteger start, long[] tab, BigInteger p) {
			delegate.markMultiplesOf(start, tab, p);
		}
	}

	@Override
	protected void stopping(Info info) {
		super.stopping(info);
		supplier.primes.close();
	}

	// 128 -> 5,232,179.3
	// 64 -> 5,861,262.1
	// 48 -> 5,913,321.2
	// 40 -> 5,904,032.0
	// 36 -> 5,386,508.0
	// 32 -> 5,973,348.8
	// 24 -> 5,519,193.2
	// 16 -> 4,522,208.0

	// 1<<19 -> 5,087,780.2
	// 1<<18 -> 6,028,986.7
	// 1<<17 -> 5,560,017.5
	SlidingWindowSieve supplier = newSlidingWindowSieve(1 << 18);

	@Override
	protected String name() {
		return SieveGap.class.getSimpleName() + "/" + supplier.name();
	}

	protected SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new SlidingWindowSieve(this, size);
	}

	@Override
	protected BigInteger nextPrimeImpl(BigInteger N) {
		BigInteger Q = supplier.getLastPrime();
		while (Q == null || Q.compareTo(N) <= 0) {
			Q = supplier.get();
			++primeCallCount;
		}
		return Q;
	}

	public static void main(String[] args) {
		new SieveGap().run();
	}
}
