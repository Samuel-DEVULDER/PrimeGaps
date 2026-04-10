package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.parallel.ParallelSieveGap;

public class DoubleBufferParallelSieveGap extends ParallelSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferParallelSieveGap().run();
	}
}
