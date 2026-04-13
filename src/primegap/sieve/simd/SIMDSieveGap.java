package primegap.sieve.simd;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.util.Java;

/**
 * SieveGap implementation using SIMD instructions.
 * <p>
 * This class overrides the method to create a sliding window sieve that
 * utilizes SIMD operations for improved performance. It checks if SIMD is
 * enabled in the Java environment and creates an instance of
 * SIMDSlidingWindowSieve accordingly.
 */
public class SIMDSieveGap extends SieveGap {
	static boolean enabled = Java.SIMD.enable();

	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new SIMDSlidingWindowSieve(this, size, doubleBuffer);
	}

	public static void main(String[] args) {
		new SIMDSieveGap().run();
	}
}
