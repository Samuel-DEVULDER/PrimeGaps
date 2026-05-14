package primegap.sieve.simd;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.util.Java;

/**
 * SieveGap implementation using SIMD instructions.
 * <p>
 * This class overrides the method to create a sliding window sieve that
 * utilizes SIMD operations for improved performance. It checks if SIMD is
 * enabled in the Java environment and creates an instance of
 * SIMDSlidingWindowSieve accordingly.
 */
public class SIMDSieveGap extends SieveGap {
	static boolean enabled = Java.SIMD.enable();

	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new SIMDSlidingWindowSieve(this, size, doubleBuffer);
	}

	public static void main(String[] args) {
		new SIMDSieveGap().run();
	}
	
	static public class DB extends SIMDSieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DB().run();
		}
	}
	
	public static class FF extends SIMDSieveGap {
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
