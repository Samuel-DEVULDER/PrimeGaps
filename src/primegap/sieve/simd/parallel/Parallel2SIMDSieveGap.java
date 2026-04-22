package primegap.sieve.simd.parallel;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.util.Java;

public class Parallel2SIMDSieveGap extends SieveGap {
	static boolean enabled = Java.SIMD.enable();
	
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new Parallel2SIMDSieve(this, size, doubleBuffer);
	}

	public static void main(String[] args) {
		new Parallel2SIMDSieveGap().run();
	}
	
	static public class DoubleBuffer extends Parallel2SIMDSieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DoubleBuffer().run();
		}
	}
	
	public static class FastForward extends Parallel2SIMDSieveGap {
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