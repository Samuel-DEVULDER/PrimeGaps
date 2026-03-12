package primegap.sieve;

public class SIMDSieveGap extends SieveGap {
	@Override
	protected SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new SIMDSlidingWindowSieve(this, size);
	}

	public static void main(String[] args) {
		new SIMDSieveGap().run();
	}
}
