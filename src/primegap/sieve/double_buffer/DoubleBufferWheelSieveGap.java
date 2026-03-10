package primegap.sieve.double_buffer;

import primegap.sieve.wheel.Wheel_210_Sieve;

public class DoubleBufferWheelSieveGap extends Wheel_210_Sieve {
	@Override
	protected SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new DoubleBufferedSieve(super.newSlidingWindowSieve(size));
	}

	public static void main(String[] args) {
		new DoubleBufferWheelSieveGap().run();
	}
}
