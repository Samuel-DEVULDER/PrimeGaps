package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.wheel.Wheel_210_Sieve;

public class DoubleBufferWheelSieveGap extends Wheel_210_Sieve {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferWheelSieveGap().run();
	}
}
