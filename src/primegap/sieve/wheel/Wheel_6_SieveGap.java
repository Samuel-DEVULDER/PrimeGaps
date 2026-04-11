package primegap.sieve.wheel;

import java.util.stream.IntStream;

import primegap.sieve.AbstractSlidingWindowSieve;

public class Wheel_6_SieveGap extends AbstractWheelSieveGap {
	class WheelSieve extends AbstracWheelSieve {
		private static final int[] JUMPS = { -1, 1, -1, -1, -1, 1, -1, 4, 2, 2, 1, 2, -1, 3, -1, 1, -1, 1, -1, 2, 1, -1,
				1, 2, -1, 1, -1, 1, -1, 3, -1, 2, 1, 2, 2, 4 };

		public WheelSieve(int size, boolean doubleBuffer) {
			super(adaptSize(size),adaptRange(adaptSize(size) * 64), doubleBuffer);
			bootstrap();
		}

		static protected int adaptSize(int size) {
			return Math.min(size, Integer.MAX_VALUE / 64);
		}

		static protected long adaptRange(int totalBits) {
			return 3L * totalBits;
		}

		@Override
		protected void bootstrap() {
			primes.add(v(5));
			setStart(v(6)); // first window starts just after small primes
			pending = IntStream.of(-2, -3, -5).iterator();
		}

		@Override
		protected long bitposToNum(int bitpos) {
			return (bitpos >>> 1) * 6L + ((bitpos & 1) == 0 ? 1 : 5);
		}

		@Override
		protected int numToBitpos(int group, int index) {
			return group * 2 + index;
		}

		@Override
		protected int residue_index(int rem) {
			return switch (rem) {
			case 1 -> 0;
			case 5 -> 1;
			default -> -1;
			};
		}

		@Override
		protected int jumps(int rem, int pMod) {
			return JUMPS[rem * 6 + pMod];
		}

		@Override
		protected int MOD() {
			return 6;
		}
	}

	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new WheelSieve(size, doubleBuffer);
	}

	public Wheel_6_SieveGap() {
		super();
	}
}