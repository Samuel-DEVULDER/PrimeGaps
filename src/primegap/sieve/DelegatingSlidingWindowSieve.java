package primegap.sieve;

import java.math.BigInteger;

public class DelegatingSlidingWindowSieve extends SlidingWindowSieve {
	protected final SlidingWindowSieve delegate;

	public DelegatingSlidingWindowSieve(SieveGap sieve, SlidingWindowSieve delegate) {
		super(sieve, delegate);
		this.delegate = delegate;
	}

	@Override
	protected String name() {
		return delegate.name();
	}

	@Override
	protected long bitposToNum(int bitpos) {
		return delegate.bitposToNum(bitpos);
	}

	@Override
	protected void bootstrap() {
		throw new UnsupportedOperationException("DelegatingSlidingWindowSieve does not support bootstrap()");
	}

	@Override
	protected void markMultiplesOf(BigInteger start, long[] tab, BigInteger p) {
		delegate.markMultiplesOf(start, tab, p);
	}
}