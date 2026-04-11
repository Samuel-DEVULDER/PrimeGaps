package primegap.sieve.wheel;

import primegap.sieve.AbstractSlidingWindowSieve;

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