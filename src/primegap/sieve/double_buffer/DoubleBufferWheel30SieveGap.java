package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.wheel.Wheel_30_SieveGap;

/**
 * SieveGap implementation using a double-buffered sliding window sieve with a
 * wheel of size 30.
 * <p>
 * This implementation combines the double-buffering technique with a wheel of
 * size 30 to further improve performance. It uses a single sliding window sieve
 * that is double-buffered and utilizes a wheel of size 30 to skip numbers that
 * are divisible by the first few primes (2, 3, and 5), which can significantly
 * reduce the number of candidates that need to be sieved.
 */
public class DoubleBufferWheel30SieveGap extends Wheel_30_SieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferWheel30SieveGap().run();
	}
}
