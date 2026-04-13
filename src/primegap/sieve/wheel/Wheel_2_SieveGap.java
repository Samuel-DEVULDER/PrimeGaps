package primegap.sieve.wheel;

import primegap.sieve.AbstractSlidingWindowSieve;

/**
 * SieveGap implementation using a wheel of size 2.
 * <p>
 * This is actually the same as the basic SieveGap, since a wheel of size 2 only
 * skips even numbers, which is the default behavior of the Sieve of
 * Eratosthenes. However, this class uses the generic wheel-based
 * implementation, which allows comparing the performance of the wheel-based
 * approach with the basic approach.
 */
public class Wheel_2_SieveGap extends AbstractWheelSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new WheelSieve(size, doubleBuffer, 2);
	}

	public Wheel_2_SieveGap() {
		super();
	}

	public static void main(String[] args) {
		new Wheel_2_SieveGap().run();
	}
}