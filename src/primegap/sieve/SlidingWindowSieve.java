package primegap.sieve;

import java.math.BigInteger;
import java.util.stream.IntStream;

/**
 * This implementation is based on the classic Sieve of Eratosthenes algorithm,
 * but it is optimized for finding large primes starting from an arbitrary
 * BigInteger position. The sieve uses a sliding window approach, where a fixed-size
 * bit array represents a range of numbers, and the algorithm marks multiples of
 * discovered primes as composite within that window. As the window slides forward,
 * new primes are discovered and used to mark their multiples in subsequent windows.
 */
public class SlidingWindowSieve extends AbstractSlidingWindowSieve {
	/**
	 * Constructs a new windowed sieve.
	 * 
	 * @param size   the size of the bit array (in longs). Window covers size*128
	 *               consecutive numbers (size*64 odd numbers). For optimal
	 *               alignment, size should be chosen such that size*128 is a
	 *               convenient range (e.g., powers of 2).
	 * @param primes the shared TreeSet to store discovered primes
	 */
	public SlidingWindowSieve(SieveGap sieve, int size, boolean doubleBuffer) {
		super(sieve, adaptSize(size), adaptRange(adaptSize(size) * 64), doubleBuffer);
		bootstrap();
	}

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
		if (n.compareTo(windowRange_bigint) >= 0)
			return;

		// Convert to bit position
		int bitPos = (n.intValue() >>> 1);

		// Check if p is small enough to have multiple occurrences
		// that is p < windowRange/2 = windowSize
		if (p.compareTo(windowSize_bigint) >= 0) {
			updateTab(tab, bitPos >>> 6, 1L << (bitPos & 63));
		} else {
			updateSeq(tab, bitPos, windowSize, p.longValue());
		}
	}

	@SuppressWarnings("unused")
	protected void updateSeq64(long[] tab, int from, long to, long step) {
		if (false && to > Integer.MAX_VALUE) {
			for (long pos = from; pos < to; pos += step) {
				updateTab(tab, (int) (pos >>> 6), 1L << (63 & pos));
			}
		} else {
			int i_to = (int) to, i_step = (int) step;
			for (int pos = from; pos < i_to; pos += i_step) {
				updateTab(tab, pos >>> 6, 1L << (pos & 63));
			}
		}
	}

	protected void updateSeq(long[] tab, int from, long to, long step) {
		if (step >= 64) {
			updateSeq64(tab, from, to, step);
		} else {
			// Accumulate bits to clear
			int i = from >>> 6;
			long mask = 1L << (63 & from);

			// Small prime: multiple odd multiples in window
			long pos = from + step;// Use long here to avoid overflow in loop

			// first occurrence appear before the first half
			while (pos < to) {
				int nextI = (int) (pos >>> 6);

				// Flush mask when moving to different long
				if (nextI != i) {
					updateTab(tab, i, mask);
					i = nextI;
					mask = 0;
				}

				mask |= (1L << (63 & pos));
				pos += step;
			}

			// Apply final mask
			updateTab(tab, i, mask);
		}
	}
}