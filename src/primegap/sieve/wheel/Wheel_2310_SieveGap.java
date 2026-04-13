package primegap.sieve.wheel;

import primegap.sieve.AbstractSlidingWindowSieve;

/**
 * SieveGap implementation using a wheel of size 2310 (2*3*5*7*11).
 * <p>
 * This wheel skips all numbers that are divisible by 2, 3, 5, 7, or 11, which
 * are the first five primes. This means that it only considers numbers that are
 * coprime to 2310, which significantly reduces the number of candidates for
 * primality testing and can improve performance for larger ranges.
 * 
 * However, this value for the wheel size is quite large, and the arrays size is
 * not cache-friendly. As a result, it does not outperform wheel 210.
 */
public class Wheel_2310_SieveGap extends AbstractWheelSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return newWheelSieve(size, doubleBuffer, 2, 3, 5, 7, 11);
	}

	public Wheel_2310_SieveGap() {
		super();
	}
}