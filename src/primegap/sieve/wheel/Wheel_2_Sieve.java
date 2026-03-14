package primegap.sieve.wheel;

import primegap.sieve.AbstractSlidingWindowSieve;

public class Wheel_2_Sieve extends WheelSieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return new WheelSieve(size, 2);
	}

	public Wheel_2_Sieve() {
		super();
	}
	
	public static void main(String[] args) {
		new Wheel_2_Sieve().run();
	}
}