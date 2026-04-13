package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.parallel.ParallelSieveGap;

/**
 * SieveGap implementation using a double-buffered sliding window sieve with
 * parallel updates.
 * <p>
 * This implementation combines the double-buffering technique with parallel
 * updates to further improve performance. It uses a single sliding window sieve
 * that is double-buffered, and it performs updates in parallel when the step
 * size is large enough.
 */
public class DoubleBufferParallelSieveGap extends ParallelSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferParallelSieveGap().run();
	}
}
