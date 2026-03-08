package primegap.sieve.wheel;

import primegap.sieve.SieveGap;

public class Wheel2Sieve extends WheelSieveGap {
	@Override
	protected SieveGap.SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new WheelSieve(size, 2);
	}

	public Wheel2Sieve() {
		super();
	}
}