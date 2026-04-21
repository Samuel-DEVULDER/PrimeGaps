package primegap.sieve.parallel;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;

public class Parallel2SieveGap extends SieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new Parallel2Sieve(this, size, doubleBuffer);
	}

	public static void main(String[] args) {
		new Parallel2SieveGap().run();
	}
	
	static public class DoubleBuffer extends Parallel2SieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DoubleBuffer().run();
		}
	}
	
	public static class FastForward extends Parallel2SieveGap {
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