package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.wheel.Wheel_210_SieveGap;

public class DoubleBufferWheel210SieveGap extends Wheel_210_SieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferWheel210SieveGap().run();
	}
}
