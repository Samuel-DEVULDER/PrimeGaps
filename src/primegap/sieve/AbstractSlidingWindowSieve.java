package primegap.sieve;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import primegap.util.IncreasingBigIntegers;
import primegap.util.Java;

/**
 * This abstract class defines the core logic of a sliding window sieve, while
 * allowing subclasses to customize the bit representation and marking strategy.
 * <p>
 * The main responsibilities of this class include:
 * <ul>
 * <li>Maintaining the current window of candidate numbers using a bit-packed
 * array.</li>
 * <li>Providing a method to mark multiples of discovered primes as
 * composite.</li>
 * <li>Implementing the logic to slide the window forward and initialize it with
 * known primes.</li>
 * <li>Supplying the next prime number on demand, handling window exhaustion and
 * sliding as needed.</li>
 * </ul>
 * Subclasses can override methods to implement specific optimizations such as
 * wheel factorization or SIMD-friendly layouts.
 */
public abstract class AbstractSlidingWindowSieve implements Supplier<BigInteger> {
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
	abstract protected void markMultiplesOf(BigInteger start, long tab[], BigInteger prime);

	final public SieveGap sieve;

	/** Collection of all discovered primes, maintained in sorted order */
	public final IncreasingBigIntegers primes;

	/** Bit-packed array representing odd prime candidates in the current window */
	public final int tabLen; // do not use tab.length directly since it may be adapted for wheel alignment or
								// SIMD padding
	protected long[] tab;

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

	protected AbstractSlidingWindowSieve(SieveGap sieve, int size, long range, boolean doubleBuffer) {
		this.sieve = sieve;

		this.primes = new IncreasingBigIntegers(1 << 24, 896779142 / 2 / 2 / 2); // 16Mb

		this.windowSize = size * 64; // Number of bits/odd numbers
		this.windowRange = range; // Actual consecutive numbers covered
		this.windowSize_bigint = v(this.windowSize);
		this.windowRange_bigint = v(this.windowRange);
		this.tab = newTab(this.tabLen = size);
		this.prefetch = doubleBuffer ? new NextWindowRunnable(this.tabLen) : null;
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

//		String s = String.format(Locale.ENGLISH, "start=%s %.1f%% ~%.1f", start, (numPrimes() * 100.0) / primes.limit(),
//				getLastPrime().doubleValue() / sieve.getPrimeCallCount());
		// System.err.print(s + "\b".repeat(s.length()));
//		System.err.println(s);

		// Sieve limit: sqrt(start + windowRange)
		limit = start.add(windowRange_bigint).sqrt();
		if (primes.isFull())
			if (primes.getLast().compareTo(limit) < 0)
				throw new RuntimeException(
						"Primes size limit is too short (" + primes.sizeLong() + "). Please increase!");
		markAllMultiples();

		last_tab = (mask = -1) ^ getTab(tab, last = 0);

		doMarking = true;
	}

	protected void markMultiplesOf(BigInteger P) {
		markMultiplesOf(start, tab, P);
	}

	protected void markAllMultiples() {
		if (prefetch != null && prefetch.swap()) {
			return;
		}
		doMarkAllMultiples(start, tab, primes, limit);
	}

	protected void doMarkAllMultiples(BigInteger start, long[] tab, IncreasingBigIntegers primes, BigInteger limit) {
		fillTab(tab, 0);
		Java.dbgTime(true);
		primes.stream().takeWhile(p -> p.compareTo(limit) <= 0).forEach(p -> markMultiplesOf(start, tab, p));
		if (Java.dbg)
			Java.dbg("all=", Java.dbgTime(false), "ms              ");
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
				last_tab = v = ~getTab(tab, last);
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
			if ((k = next()) < 0) {
				pending = EMPTY;
				do
					slideWindow();
				while ((k = next()) < 0);
			}
		}

		// Convert bit position to actual odd number: start + 2*k
		BigInteger prime = start.add(v(bitposToNum(k)));
		// System.err.println("Candidate bit=" + k + ", num=" + bitposToNum(k) + ",
		// prime=" + prime + " (rem="
		// + prime.mod(v(30)) + ")");
		assert prime.isProbablePrime(10);

		primes.add(prime);

		if (doMarking) {
			if (prime.compareTo(limit) <= 0) {
				Java.dbgTime(true);
				markMultiplesOf(start, tab, prime);
				last_tab = ~getTab(tab, last >>> last_shift);
				if (Java.dbg)
					Java.dbg("Marking multiples of ", prime, " in ", Java.dbgTime(false), "ms.");
			} else {
				doMarking = false;
				if (Java.dbg)
					Java.dbg("disabled marking for ", start);
			}
		}

		return lastPrime = prime;
	}

	// Slide window by windowRange (128 * tab.length consecutive numbers)
	protected void slideWindow() {
		setStart(start.add(windowRange_bigint));
	}

	protected String name() {
		return prefetch != null ? "/DoubleBuffer" : "";
	}

	final NextWindowRunnable prefetch;

	class NextWindowRunnable implements Runnable {
		CompletableFuture<Void> nextWindowFuture = null;

		long[] nextTab;
		BigInteger currStart, nextStart, limit;

		NextWindowRunnable(int size) {
			nextTab = newTab(size);
		}

		boolean swap() {
			boolean ret = false;
			if (nextWindowFuture != null)
				try {
					nextWindowFuture.get();
					long[] t = nextTab;
					nextTab = tab;
					tab = t;
					ret = true;
				} catch (InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
			nextWindowFuture = ready(start) ? CompletableFuture.runAsync(this) : null;
			return ret;
		}

		boolean ready(BigInteger start) {
			if (!start.equals(this.currStart)) {
				this.currStart = start;
				this.nextStart = start.add(windowRange_bigint);
				this.limit = nextStart.add(windowRange_bigint).sqrt();
			}
			BigInteger last = primes.getLast();
			return last != null && last.compareTo(limit) >= 0;
		}

		@Override
		public void run() {
			doMarkAllMultiples(nextStart, nextTab, primes, limit);
		}
	}
}