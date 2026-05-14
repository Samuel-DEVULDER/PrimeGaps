package primegap.sieve.simd.parallel;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.util.Java;

public class ParallelPrimesSIMDSieveGap extends SieveGap {
	static boolean enabled = Java.SIMD.enable();
	
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new ParallelPrimesSIMDSieve(this, size, doubleBuffer);
	}

	public static void main(String[] args) {
		new ParallelPrimesSIMDSieveGap().run();
	}
	
	static public class DoubleBuffer extends ParallelPrimesSIMDSieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DoubleBuffer().run();
		}
	}
	
	public static class FastForward extends ParallelPrimesSIMDSieveGap {
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