package primegap.sieve.double_buffer;

import primegap.sieve.SlidingWindowSieveGap;
import primegap.sieve.SlidingWindowSieve;

public class DoubleBufferSieveGap extends SlidingWindowSieveGap {
	@Override
	protected SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new DoubleBufferedSieve(super.newSlidingWindowSieve(size));
	}

	public static void main(String[] args) {
		new DoubleBufferSieveGap().run();
	}
}
