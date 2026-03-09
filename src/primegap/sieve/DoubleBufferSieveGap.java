package primegap.sieve;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class DoubleBufferSieveGap extends SieveGap {
	class DoubleBufferWindowedSieve extends SlidingWindowSieve {
		final NextWindowRunnable prefetch;
		long[] nextTab = null;

		public DoubleBufferWindowedSieve(int size) {
			super(size);
			prefetch = new NextWindowRunnable(size);
		}

		protected void markAllMultiples() {
			if (prefetch != null) {
				long[] t = prefetch.swap(start, tab);
				if (t != null) {
					tab = t;
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
					this.nextStart = start.add(windowRange_);
					this.limit = start.add(windowRange_.shiftLeft(1)).sqrt();
				}
				BigInteger last = primes.getLast();
				return last != null && last.compareTo(limit) >= 0;
			}

			protected void markMultiplesOf(BigInteger P) {
				DoubleBufferWindowedSieve.this.markMultiplesOf(nextStart, nextTab, P);
			}

			@Override
			public void run() {
				fillTab(nextTab, 0);
				long now = timer.getAsLong();
				primes.stream().takeWhile(p -> p.compareTo(limit) <= 0).forEach(this::markMultiplesOf);
				dbg("all(prefetch)=", (timer.getAsLong() - now) / 1e6, "ms              ");
			}
		}
	}

	@Override
	protected SieveGap.SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new DoubleBufferWindowedSieve(size/2);
	}

	public static void main(String[] args) {
		new DoubleBufferSieveGap().run();
	}

}
