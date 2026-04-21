package primegap.sieve.simd.parallel;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.simd.SIMDSieveGap;
import primegap.util.Java;

/**
 * ParallelSIMDSieveGap is a SieveGap implementation that combines SIMD
 * instructions with parallel processing.
 * <p>
 * This class overrides the method to create a sliding window sieve that
 * utilizes both SIMD operations and parallel processing for improved
 * performance. It checks if SIMD is enabled in the Java environment and creates
 * an instance of ParallelSIMDSieve accordingly.
 */
public class ParallelSIMDSieveGap extends SIMDSieveGap {
	static boolean enabled = Java.SIMD.enable();

	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new ParallelSIMDSieve(this, size, doubleBuffer);
	}

	public static void main(String[] args) {
		new ParallelSIMDSieveGap().run();
	}
	
	static public class DoubleBuffer extends ParallelSIMDSieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DoubleBuffer().run();
		}
	}
	
	public static class FastForward extends ParallelSIMDSieveGap {
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
