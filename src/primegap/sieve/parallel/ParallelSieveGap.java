package primegap.sieve.parallel;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;

/**
 * SieveGap implementation using parallel updates.
 * <p>
 * This implementation overrides the method to create a sliding window sieve that
 * performs updates in parallel when the step size is large enough. It uses a
 * single sliding window sieve that is not double-buffered, and it performs
 * updates in parallel when the step size is large enough.
 */
public class ParallelSieveGap extends SieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size,boolean doubleBuffer) {
		return new ParallelSieve(this, size, doubleBuffer);
	}

	public static void main(String[] args) {
		new ParallelSieveGap().run();
	}

}
