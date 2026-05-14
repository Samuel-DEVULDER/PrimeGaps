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
	
	static public class DB extends ParallelPrimesSIMDSieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DB().run();
		}
	}
	
	public static class FF extends ParallelPrimesSIMDSieveGap {
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