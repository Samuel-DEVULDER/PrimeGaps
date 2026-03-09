package primegap.sieve.wheel;

import primegap.sieve.SieveGap;

public class Wheel_2_Sieve extends WheelSieveGap {
	@Override
	protected SieveGap.SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new WheelSieve(size, 2);
	}

	public Wheel_2_Sieve() {
		super();
	}
}