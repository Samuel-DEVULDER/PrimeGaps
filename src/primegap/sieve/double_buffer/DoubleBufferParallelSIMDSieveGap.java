package primegap.sieve.double_buffer;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.simd.parallel.ParallelSIMDSieveGap;

/**
 * SieveGap implementation using a double-buffered sliding window sieve with
 * parallel updates and SIMD instructions.
 * <p>
 * This implementation combines the double-buffering technique with parallel
 * updates and SIMD instructions to further improve performance. It uses a single
 * sliding window sieve that is double-buffered, and it performs updates in
 * parallel when the step size is large enough, while also utilizing SIMD
 * operations for improved performance.
 */
public class DoubleBufferParallelSIMDSieveGap extends ParallelSIMDSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferParallelSIMDSieveGap().run();
	}
	
	public static class FastForward extends DoubleBufferParallelSIMDSieveGap {
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
