package primegap.sieve.parallel;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;

/**
 * SieveGap implementation using parallel updates.
 * <p>
 * This implementation overrides the method to create a sliding window sieve that
 * performs updates in parallel when the step size is large enough. It uses a
 * single sliding window sieve that is not double-buffered, and it performs
 * updates in parallel when the step size is large enough.
 */
public class ParallelSeqSieveGap extends SieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size,boolean doubleBuffer) {
		return new ParallelSeqSieve(this, size, doubleBuffer);
	}

	public static void main(String[] args) {
		new ParallelSeqSieveGap().run();
	}

	/**
	 * SieveGap implementation using a double-buffered sliding window sieve with
	 * parallel updates.
	 * <p>
	 * This implementation combines the double-buffering technique with parallel
	 * updates to further improve performance. It uses a single sliding window sieve
	 * that is double-buffered, and it performs updates in parallel when the step
	 * size is large enough.
	 */
	static public class DB extends ParallelSeqSieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DB().run();
		}
	}
	
	public static class FF extends ParallelSeqSieveGap {
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
