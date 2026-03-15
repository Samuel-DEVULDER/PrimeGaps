package primegap.sieve.simd.parallel;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.simd.SIMDSieveGap;
import primegap.util.Java;

public class ParallelSIMDSieveGap extends SIMDSieveGap {
	static boolean enabled = Java.SIMD.enable();
	
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new ParallelSIMDSieve(this, size);
	}

	public static void main(String[] args) {
		new ParallelSIMDSieveGap().run();
	}
}
