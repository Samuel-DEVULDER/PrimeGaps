package primegap.sieve.simd;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.util.Java;

public class SIMDSieveGap extends SieveGap {
	static boolean enabled = Java.SIMD.enable();
	
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new SIMDSlidingWindowSieve(this, size);
	}

	public static void main(String[] args) {
		new SIMDSieveGap().run();
	}
}
