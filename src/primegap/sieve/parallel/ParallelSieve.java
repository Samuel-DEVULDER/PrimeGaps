package primegap.sieve.parallel;

import primegap.sieve.SieveGap;
import primegap.sieve.SlidingWindowSieve;
import primegap.util.Java;

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
		if (step < 4*64L) {
			super.updateSeq(tab, from, to, step);
		} else {
			Java.rangeWithStep(from, to, step).parallel()
					.forEach(bitpos -> updateTab(tab, (int) (bitpos >>> 6), 1L << (63 & bitpos)));
		}
	}
}
