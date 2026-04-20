package primegap.sieve.double_buffer;

import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.simd.SIMDSieveGap;
import primegap.sieve.simd.SIMDSlidingWindowSieve;

/**
 * SieveGap implementation using a double-buffered sliding window sieve with
 * SIMD instructions.
 * <p>
 * This implementation combines the double-buffering technique with SIMD
 * operations to further improve performance. It uses a single sliding window
 * sieve that is double-buffered and utilizes SIMD instructions for faster
 * sieving.
 */
public class DoubleBufferSIMDSieveGap extends SIMDSieveGap {
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new SIMDSlidingWindowSieve(this, size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferSIMDSieveGap().run();
	}
	
	public static class FastForward extends SIMDSieveGap {
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
