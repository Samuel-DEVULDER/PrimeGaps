package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.sieve.SlidingWindowSieve;

public class DoubleBufferSieveGap extends SieveGap {
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new SlidingWindowSieve(this, size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferSieveGap().run();
	}
}
