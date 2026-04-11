package primegap.sieve.wheel;

import primegap.sieve.AbstractSlidingWindowSieve;

public class Wheel_210_Sieve extends AbstractWheelSieveGap {
	// =========================================================================
	// Wheel210Sieve - 48 candidates per 210 integers (~77% fewer than odd-only)
	// =========================================================================
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return newWheelSieve(size, doubleBuffer, 2, 3, 5, 7);
	}

	public Wheel_210_Sieve() {
		super();
	}
}