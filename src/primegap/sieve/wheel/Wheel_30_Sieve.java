package primegap.sieve.wheel;

import primegap.sieve.AbstractSlidingWindowSieve;

public class Wheel_30_Sieve extends WheelSieveGap {

	// =========================================================================
	// Wheel30Sieve - 8 candidates per 30 integers (~73% fewer than odd-only)
	// =========================================================================

	/**
	 * Wheel-30 sieve. Skips all multiples of 2, 3, 5. Only the wheel-specific
	 * methods are overridden; everything else is inherited.
	 */
	class OptWheelSieve extends WheelSieve {
		public OptWheelSieve(int size) {
			super(size, 2, 3, 5);
		}

		/** bit k -> (k >> 3) * 30 + RESIDUES[k & 7] (shift/mask since bpa=8=2^3) */
		@Override
		protected long bitposToNum(int bitpos) {
			bitpos >>>= 3;
			return (((bitpos << 4) - bitpos) << 1) + RESIDUES[bitpos & 7];
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
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new OptWheelSieve(size);
	}
	
	public Wheel_30_Sieve() {
		super();
	}
}