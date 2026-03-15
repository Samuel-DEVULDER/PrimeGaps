package primegap.sieve.double_buffer;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import primegap.AbstractPrimeGap;
import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.util.Machine;

class DoubleBufferedSieve extends AbstractSlidingWindowSieve.Delegating {
	final NextWindowRunnable prefetch;
	long[] nextTab = null;

	public DoubleBufferedSieve(AbstractSlidingWindowSieve delegate) {
		super(delegate.sieve, delegate);
		prefetch = new NextWindowRunnable(getTab().length);
	}

	@Override
	protected String name() {
		return "DoubleBuffer/" + super.name();
	}

	protected void markAllMultiples() {
		if (prefetch != null) {
			long[] t = prefetch.swap(delegate.getStart(), delegate.getTab());
			if (t != null) {
				setTab(t);
				return;
			}
		}
		super.markAllMultiples();
	}

	class NextWindowRunnable implements Runnable {
		CompletableFuture<Void> nextWindowFuture = null;

		long[] nextTab;
		BigInteger start, nextStart, limit;

		NextWindowRunnable(int size) {
			nextTab = new long[size];
		}

		public long[] swap(BigInteger start, long[] tab) {
			long[] t = null;
			if (nextWindowFuture != null)
				try {
					nextWindowFuture.get();
					t = nextTab;
					nextTab = tab;
				} catch (InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
			if (ready(start))
				nextWindowFuture = CompletableFuture.runAsync(this);
			return t;
		}

		boolean ready(BigInteger start) {
			if (!start.equals(this.start)) {
				this.start = start;
				this.nextStart = start.add(windowRange_bigint);
				this.limit = start.add(windowRange_bigint.shiftLeft(1)).sqrt();
			}
			BigInteger last = primes.getLast();
			return last != null && last.compareTo(limit) >= 0;
		}

		protected void markMultiplesOf(BigInteger P) {
			DoubleBufferedSieve.this.markMultiplesOf(nextStart, nextTab, P);
		}

		@Override
		public void run() {
			fillTab(nextTab, 0);
			long now = Machine.getCpuTimeNano();
			primes.stream().takeWhile(p -> p.compareTo(limit) <= 0).forEach(this::markMultiplesOf);
			AbstractPrimeGap.dbg("all(prefetch)=", (Machine.getCpuTimeNano() - now) / 1e6,
					"ms              ");
		}
	}
}
