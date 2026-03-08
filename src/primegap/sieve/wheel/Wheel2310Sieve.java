package primegap.sieve.wheel;

import primegap.sieve.SieveGap;

public class Wheel2310Sieve extends WheelSieveGap {
	@Override
	protected SieveGap.SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new WheelSieve(size, 2, 3, 5, 7, 11);
	}

	public Wheel2310Sieve() {
		super();
	}
}