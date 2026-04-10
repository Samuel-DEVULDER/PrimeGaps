package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.simd.SIMDSieveGap;
import primegap.sieve.simd.SIMDSlidingWindowSieve;

public class DoubleBufferSIMDSieveGap extends SIMDSieveGap {
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new SIMDSlidingWindowSieve(this, size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferSIMDSieveGap().run();
	}
}
