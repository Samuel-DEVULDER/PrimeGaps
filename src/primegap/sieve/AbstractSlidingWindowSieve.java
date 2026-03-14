package primegap.sieve;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.function.Supplier;

import primegap.util.IncreasingBigIntegers;
import primegap.util.Machine;

/**
 * Windowed Sieve of Eratosthenes optimized for finding large primes starting
 * from an arbitrary BigInteger position.
 * <p>
 * The sieve works incrementally: as primes are discovered, they are immediately
 * used to mark their multiples as composite in the current and future windows.
 * </p>
 */
public abstract class AbstractSlidingWindowSieve implements Supplier<BigInteger> {
	final public SieveGap sieve;

	/** Collection of all discovered primes, maintained in sorted order */
	public final IncreasingBigIntegers primes;

	/** Bit-packed array representing odd prime candidates in the current window */
	public final int tabLen; // do not use tab.length directly since it may be adapted for wheel alignment or
								// SIMD padding
	private long[] tab;

	/** Number of odd candidates represented */
	protected final int windowSize;
	final BigInteger windowSize_bigint; // BigInteger version of windowSize for easy calculations

	/** Real range covered by the window */
	protected final long windowRange;
	protected final BigInteger windowRange_bigint; // BigInteger version of windowRange for easy calculations

	/** BigInteger versions of windowSize and windowRange for easy calculations */
	static BigInteger v(long l) {
		return BigInteger.valueOf(l);
	}

	/** BigInteger constants for convenience */
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

	// Delegate constructor for creating a new sieve with the same configuration as
	// an existing one
	AbstractSlidingWindowSieve(SieveGap sieve, AbstractSlidingWindowSieve delegate) {
		this.sieve = delegate.sieve;
		this.primes = delegate.primes;
		this.setTab(delegate.getTab());
		this.tabLen = delegate.tabLen;
		this.windowSize = delegate.windowSize;
		this.windowRange = delegate.windowRange;
		this.windowRange_bigint = delegate.windowRange_bigint;
		this.windowSize_bigint = delegate.windowSize_bigint;
		this.start = delegate.start;
		this.limit = delegate.limit;
		this.pending = delegate.pending;
		this.last = delegate.last;
		this.mask = delegate.mask;
		this.last_tab = delegate.last_tab;
	}

	protected AbstractSlidingWindowSieve(SieveGap sieve, int size, long range) {
		this.sieve = sieve;

		this.primes = new IncreasingBigIntegers(1 << 24, 896779142 / 2 / 2 / 2); // 16Mb

		this.windowSize = size * 64; // Number of bits/odd numbers
		this.windowRange = range; // Actual consecutive numbers covered
		this.windowSize_bigint = v(this.windowSize);
		this.windowRange_bigint = v(this.windowRange);
		this.setTab(newTab(this.tabLen = size));
	}

	// -------------------------------------------------------------------
	// Wheel hooks - override these in subclasses
	// -------------------------------------------------------------------

	/**
	 * bootstraps the sieve by setting the initial window start and pending
	 * iterator. Should be called by the "final" constructor once everything else is
	 * ready.
	 */
	abstract protected void bootstrap();

	/**
	 * Converts bit index k to numeric offset from start. Default (wheel2): bit k ->
	 * 2k.
	 */
	abstract protected long bitposToNum(int bitpos);

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
				(numPrimes() * 100.0) / primes.limit(), getLastPrime().doubleValue() / sieve.getPrimeCallCount());
		System.err.print(s + "\b".repeat(s.length()));

		// Sieve limit: sqrt(start + windowRange)
		limit = this.getStart().add(windowRange_bigint).sqrt();
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
		long now = Machine.getCpuTimeNano();
		primes.stream().takeWhile(p -> p.compareTo(limit) <= 0).forEach(this::markMultiplesOf);
		SieveGap.dbg("all=", (Machine.getCpuTimeNano() - now) / 1e6, "ms              ");
	}

	/**
	 * Clears the bit for each odd multiple of p within the current window. Uses bit
	 * S * accumulation to minimize memory accesses (one write per long).
	 * <p>
	 * Since only odd numbers are represented, bit k corresponds to number start +
	 * 2*k. For an odd prime p, consecutive odd multiples are spaced by 2*p in
	 * number space, which corresponds to spacing p in bit position space.
	 * </p>
	 * 
	 * @param p an odd prime number
	 */
	abstract protected void markMultiplesOf(BigInteger start, long tab[], BigInteger p);

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

		if (pending.hasNext()) {
			k = pending.nextInt();
			if (k < 0)
				return lastPrime = v(-k);
		} else {
			while ((k = next()) < 0) {
				long now = Machine.getCpuTimeNano();
				if (pending != EMPTY) {
					pending = EMPTY;
				}
				slideWindow();
				SieveGap.dbg("Slide done (", (Machine.getCpuTimeNano() - now) / 1e9,
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
				long now = Machine.getCpuTimeNano();
				markMultiplesOf(getStart(), getTab(), prime);
				last_tab = ~getTab(getTab(), last >>> last_shift);
				SieveGap.dbg("Marking multiples of ", prime, " in ",
						(Machine.getCpuTimeNano() - now) / 1e6, "ms.");
			} else {
				doMarking = false;
				SieveGap.dbg("disabled marking for ", getStart());
			}
		}

		return lastPrime = prime;
	}

	// Slide window by windowRange (128 * tab.length consecutive numbers)
	protected void slideWindow() {
		setStart(getStart().add(windowRange_bigint));
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

	public static class Delegating extends AbstractSlidingWindowSieve {
		protected final AbstractSlidingWindowSieve delegate;

		public Delegating(SieveGap sieve, AbstractSlidingWindowSieve delegate) {
			super(sieve, delegate);
			this.delegate = delegate;
		}

		protected void bootstrap() {
			throw new UnsupportedOperationException("bootstrap should be called on the delegate, not the wrapper");
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
		protected void markMultiplesOf(BigInteger start, long[] tab, BigInteger p) {
			delegate.markMultiplesOf(start, tab, p);
		}
	}
}