package primegap.sieve.wheel;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;

/**
 * SieveGap implementation using a wheel of size 210.
 * <p>
 * This wheel skips all numbers that are divisible by 2, 3, 5, or 7, which are
 * the first four primes. This results in a wheel of size 210 (the product of
 * these primes) and 48 candidates per 210 integers, which is about 77% fewer
 * candidates than the odd-only wheel (which has 105 candidates per 210
 * integers).
 * 
 * This seems to be the sweet spot for performance, as it significantly reduces
 * the number of candidates while still allowing for efficient sieving.
 */
public class Wheel_210_SieveGap extends AbstractWheelSieveGap {
	// =========================================================================
	// Wheel210Sieve - 48 candidates per 210 integers (~77% fewer than odd-only)
	// =========================================================================
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return newWheelSieve(size, doubleBuffer, 2, 3, 5, 7);
	}

	public Wheel_210_SieveGap() {
		super();
	}

	static public class DB extends Wheel_210_SieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DB().run();
		}
	}
	
	public static class FF extends Wheel_210_SieveGap {
		public FF() {
			gapCounts = null;
		}

		@Override
		protected BigInteger fastForward(BigInteger P, int gap) {
			return supplier.fastForward(P, gap, this::addPrimeCount);
		}

		public static void main(String[] args) {
			new FF().run();
		}
		
		static public class DB extends FF {
			@Override
			protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
				return newSlidingWindowSieve(size, true);
			}

			public static void main(String[] args) {
				new DB().run();
			}
		}
	}
}