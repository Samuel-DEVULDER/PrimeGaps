package primegap.sieve.wheel;

import primegap.sieve.AbstractSlidingWindowSieve;

public class Wheel_2310_SieveGap extends AbstractWheelSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return newWheelSieve(size, doubleBuffer, 2, 3, 5, 7, 11);
	}

	public Wheel_2310_SieveGap() {
		super();
	}
}