package primegap.sieve.wheel;

import primegap.sieve.AbstractSlidingWindowSieve;

public class Wheel_2310_Sieve extends WheelSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newWheelSieve(size, 2, 3, 5, 7, 11);
	}

	public Wheel_2310_Sieve() {
		super();
	}
}