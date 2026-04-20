package primegap.sieve.double_buffer;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.wheel.Wheel_210_SieveGap;

/**
 * SieveGap implementation using a double-buffered sliding window sieve with a
 * wheel of size 210.
 * <p>
 * This implementation combines the double-buffering technique with a wheel of
 * size 210, which skips numbers that are divisible by the first 4 primes (2, 3,
 * 5, and 7). This can significantly reduce the number of candidates that need
 * to be sieved, especially for larger ranges, and can improve performance.
 */
public class DoubleBufferWheel210SieveGap extends Wheel_210_SieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferWheel210SieveGap().run();
	}

	public static class FastForward extends DoubleBufferWheel210SieveGap {
		public FastForward() {
			gapCounts = null;
		}

		@Override
		protected BigInteger fastForward(BigInteger P, int gap) {
			return supplier.fastForward(P, gap, this::addPrimeCount);
		}

		public static void main(String[] args) {
			new FastForward().run();
		}
	}
}
