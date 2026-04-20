package primegap.sieve.double_buffer;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.sieve.SlidingWindowSieve;

/**
 * SieveGap implementation using a double-buffered sliding window sieve.
 * <p>
 * This implementation uses a single sliding window sieve that is
 * double-buffered, meaning it maintains two buffers that it alternates between
 * for sieving. This allows it to start sieving the next window while the
 * current window is still being processed, which can improve performance by
 * overlapping computation and memory access.
 * 
 * This is actually the algorithm that performs best in practice, even compared
 * to the more complex ones.
 */
public class DoubleBufferSieveGap extends SieveGap {
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new SlidingWindowSieve(this, size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferSieveGap().run();
	}
	
	public static class FastForward extends DoubleBufferSieveGap {
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
