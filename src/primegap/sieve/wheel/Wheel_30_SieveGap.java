package primegap.sieve.wheel;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;

/**
 * SieveGap implementation using a wheel of size 30.
 * <p>
 * This wheel skips all multiples of 2, 3, and 5, which are the first three
 * primes. This results in only 8 candidates per 30 integers, which is about 73%
 * fewer than the odd-only approach (which has 15 candidates per 30 integers).
 * <p>
 * The wheel-specific methods are implemented in the OptWheelSieve inner class,
 * which uses the fact that there are 8 residues, a nice power of two which
 * allows using bit-masking in place of arithmetic modulo.
 */
public class Wheel_30_SieveGap extends AbstractWheelSieveGap {

	// =========================================================================
	// Wheel30Sieve - 8 candidates per 30 integers (~73% fewer than odd-only)
	// =========================================================================

	/**
	 * Wheel-30 sieve. Skips all multiples of 2, 3, 5. Only the wheel-specific
	 * methods are overridden; everything else is inherited.
	 */
	class OptWheelSieve extends WheelSieve {
		public OptWheelSieve(int size, boolean doubleBuffer) {
			super(size, doubleBuffer, 2, 3, 5);
		}

		/** bit k -> (k >> 3) * 30 + RESIDUES[k & 7] (shift/mask since bpa=8=2^3) */
		@Override
		protected long bitposToNum(int bitpos) {
			return (bitpos >>> 3) * 30 + RESIDUES[bitpos & 7];
		}

		protected int numToBitpos(int group, int index) {
			return (group << 3) + index;
		}

		@Override
		protected int MOD() {
			return 30;
		}
	}

	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new OptWheelSieve(size, doubleBuffer);
	}

	public Wheel_30_SieveGap() {
		super();
	}
	
	public static void main(String[] args) {
		new Wheel_30_SieveGap().run();
	}
	
	static public class DoubleBuffer extends Wheel_30_SieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DoubleBuffer().run();
		}
	}
	
	public static class FastForward extends Wheel_30_SieveGap {
		public FastForward() {
			gapCounts = null;
		}

		@Override
		protected BigInteger fastForward(BigInteger P, int gap) {
			return supplier.fastForward(P, gap, this::addPrimeCount);
		}

		public static void main(String[] args) {
			new FastForward().run();
		}
		
		static public class DoubleBuffer extends FastForward {
			@Override
			protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
				return newSlidingWindowSieve(size, true);
			}

			public static void main(String[] args) {
				new DoubleBuffer().run();
			}
		}
	}

}