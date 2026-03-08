package primegap.sieve.wheel;

import primegap.sieve.SieveGap;

public class Wheel210Sieve extends WheelSieveGap {
	// =========================================================================
	// Wheel210Sieve - 48 candidates per 210 integers (~77% fewer than odd-only)
	// =========================================================================

	@Override
	protected SieveGap.SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new WheelSieve(size, 2, 3, 5, 7);
	}

	public Wheel210Sieve() {
		super();
	}
}