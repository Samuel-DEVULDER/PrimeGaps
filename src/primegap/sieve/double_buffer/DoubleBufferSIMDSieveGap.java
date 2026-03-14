package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.simd.SIMDSieveGap;

public class DoubleBufferSIMDSieveGap extends SIMDSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new DoubleBufferedSieve(super.newSlidingWindowSieve(size));
	}

	public static void main(String[] args) {
		new DoubleBufferSIMDSieveGap().run();
	}
}
