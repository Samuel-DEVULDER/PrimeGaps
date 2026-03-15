package primegap.sieve.parallel;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;

public class ParallelSieveGap extends SieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new ParallelSieve(this, size);
	}

	public static void main(String[] args) {
		new ParallelSieveGap().run();
	}

}
