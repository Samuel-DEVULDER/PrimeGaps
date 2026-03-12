package primegap.sieve.simd;

import primegap.sieve.SieveGap;
import primegap.sieve.SlidingWindowSieve;
import primegap.util.Machine;

public class SIMDSieveGap extends SieveGap {
	static public boolean enabled = Machine.enableSIMD();
	
	@Override
	protected SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new SIMDSlidingWindowSieve(this, size);
	}

	public static void main(String[] args) {
		new SIMDSieveGap().run();
	}
}
