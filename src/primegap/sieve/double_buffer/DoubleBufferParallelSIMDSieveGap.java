package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.simd.parallel.ParallelSIMDSieveGap;

public class DoubleBufferParallelSIMDSieveGap extends ParallelSIMDSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferParallelSIMDSieveGap().run();
	}
}
