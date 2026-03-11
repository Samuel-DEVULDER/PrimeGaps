package primegap.sieve.wheel;

import primegap.sieve.SlidingWindowSieve;

public class Wheel_2_Sieve extends WheelSieveGap {
	@Override
	protected SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new WheelSieve(size, 2);
	}

	public Wheel_2_Sieve() {
		super();
	}
}