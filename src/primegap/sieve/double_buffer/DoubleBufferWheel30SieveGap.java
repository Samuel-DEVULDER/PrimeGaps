package primegap.sieve.double_buffer;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.wheel.Wheel_30_SieveGap;

public class DoubleBufferWheel30SieveGap extends Wheel_30_SieveGap {
	@Override
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, true);
	}

	public static void main(String[] args) {
		new DoubleBufferWheel30SieveGap().run();
	}
}
