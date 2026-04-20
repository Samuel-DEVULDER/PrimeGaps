package primegap.sieve.parallel;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.sieve.SlidingWindowSieve;
import primegap.util.Java;

/**
 * Parallel version of the sliding window sieve.
 * <p>
 * This class extends the SlidingWindowSieve and overrides the method to update
 * the sieve table in parallel when the step size is large enough. It uses
 * Java's parallel streams to efficiently update the sieve table across multiple
 * threads, improving performance for larger step sizes.
 */
class ParallelSieve extends SlidingWindowSieve {
	public ParallelSieve(SieveGap sieve, int size, boolean doubleBuffer) {
		super(sieve, size, doubleBuffer);
	}

	@Override
	protected String name() {
		if (name == null)
			name = super.name() + "/Parallel";
		return name;
	}

	private String name;

//	static final VarHandle VH = MethodHandles.arrayElementVarHandle(long[].class);
//
//	@Override
//	protected void updateTab(long[] tab, int i, long mask) {
//		VH.getAndBitwiseOr(tab, i, mask);
//	}

	@Override
	protected void updateSeq(long[] tab, int from, long to, long step) {
		if (step < 16*64L) {
			super.updateSeq(tab, from, to, step);
		} else {
			Java.rangeWithStep(from, to, step).parallel()
					.forEach(bitpos -> updateTab(tab, (int) (bitpos >>> 6), 1L << (63 & bitpos)));
		}
	}
	
	static public class ParallelSieveGap extends SieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size,boolean doubleBuffer) {
			return new ParallelSieve(this, size, doubleBuffer);
		}

		public static void main(String[] args) {
			new ParallelSieveGap().run();
		}

	}
}
