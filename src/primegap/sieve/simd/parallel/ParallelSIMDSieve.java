package primegap.sieve.simd.parallel;

import primegap.sieve.SieveGap;
import primegap.sieve.simd.SIMDSlidingWindowSieve;
import primegap.util.Java;

class ParallelSIMDSieve extends SIMDSlidingWindowSieve {
	public ParallelSIMDSieve(SieveGap sieve, int size, boolean doubleBuffer) {
		super(sieve, size, doubleBuffer);
	}

//	static final VarHandle VH = MethodHandles.arrayElementVarHandle(long[].class);
//
//	@Override
//	protected void updateTab(long[] tab, int i, long mask) {
//		VH.getAndBitwiseOr(tab, i, mask);
//	}

	@Override
	protected void updateSeq64(long[] tab, int from, long to, long step) {
		if (step < 3*64) {
			super.updateSeq64(tab, from, to, step);
		} else {
			Java.rangeWithStep(from, to, step).parallel()
					.forEach(bitpos -> updateTab(tab, (int) (bitpos >>> 6), 1L << (63 & bitpos)));
		}
	}
}
