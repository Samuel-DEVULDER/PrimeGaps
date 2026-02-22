import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.ref.Cleaner;
import java.math.BigInteger;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

public abstract class AbstractPrimeGap {
	static BigInteger v(long l) {
		return BigInteger.valueOf(l);
	}

	static BigInteger TWO = v(2), ONE = v(1), ZERO = v(0);

	void printf(String fmt, Object... args) {
		System.out.printf(Locale.ENGLISH, fmt, args);
	}

	public static class IncreasingBigIntegers extends AbstractCollection<BigInteger> implements Collection<BigInteger> {
		private static final Cleaner CLEANER = Cleaner.create();

		private static class CleanupState implements Runnable {
			private File f;
			private RandomAccessFile raf;
			private Thread hook;

			CleanupState(File f, RandomAccessFile raf) {
				this.raf = raf;
				this.f = f;
				this.hook = new Thread(this) {
					@Override
					public void run() {
						hook = null;
						super.run();
					}
				};
				Runtime.getRuntime().addShutdownHook(this.hook);
			}

			@Override
			public void run() {
				Thread hk = hook;
				if (hk != null && Runtime.getRuntime().removeShutdownHook(hk)) {
					hook = null;
				}
				RandomAccessFile loc = raf;
				if (loc != null) {
					try {
						loc.close();
						raf = null;
					} catch (IOException e) {
					}
				}
				File loc2 = f;
				if (loc2 != null && loc2.delete()) {
					loc2.delete();
					System.out.println("Temp file cleaned up: " + loc2);
					f = null;
				}
			}
		}

		final File dbFile;
		final RandomAccessFile raf;

		final byte[] buffer;
		int index;

		BigInteger last, first;
		long size;

		public BigInteger getFirst() {
			return first;
		}

		public BigInteger getLast() {
			return last;
		}

		public IncreasingBigIntegers(int size) {
			try {
				dbFile = File.createTempFile("gap", ".primes");
				dbFile.deleteOnExit();
				System.err.println(dbFile);
				raf = new RandomAccessFile(dbFile, "rw");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
			buffer = new byte[size];
			size = index = 0;

			var cl = new CleanupState(dbFile, raf);
			CLEANER.register(this, cl);
		}

		public IncreasingBigIntegers(Collection<BigInteger> col) throws IOException {
			this(4096);
			col.stream().sorted().distinct().forEach(this::add);
		}

		@Override
		public int size() {
			return (int) Long.min(Integer.MAX_VALUE, size);
		}

		public long sizeLong() {
			return size;
		}

		static final BigInteger ONE_TWO_SEVEN = BigInteger.valueOf(127);

		void pushByte(int b) throws IOException {
			buffer[index++] = (byte) b;
			if (index == buffer.length) {
				raf.seek(raf.length());
				raf.write(buffer, 0, index);
				index = 0;
			}
		}

		void push(BigInteger n) throws IOException {
			while (n.compareTo(ONE_TWO_SEVEN) > 0) {
				pushByte(n.and(ONE_TWO_SEVEN).byteValue());
				n = n.shiftRight(7);
			}
			pushByte(n.and(ONE_TWO_SEVEN).byteValue() | 128);
		}

		@Override
		public boolean add(BigInteger e) {
			BigInteger delta = last == null ? e : e.subtract(last);
			if (delta.signum() <= 0)
				return false;
			if (first == null)
				first = e;
			last = e;
			if (++size <= Integer.MAX_VALUE)
				try {
					push(delta);
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}
			return true;
		}

		@Override
		public Iterator<BigInteger> iterator() {
			return new Iterator<BigInteger>() {
				byte[] buf = new byte[buffer.length];
				BigInteger next;
				long pos = 0;
				int buf_len, buf_idx;

				{
					advance();
				}

				@Override
				public boolean hasNext() {
					return next != null;
				}

				@Override
				public BigInteger next() {
					BigInteger next = this.next;
					advance();
					return next;
				}

				boolean fill_buf() {
					try {
						if (pos < 0) {
							return false;
						}
						raf.seek(pos);
						buf_len = raf.read(buf);
						if (buf_len < 0) {
							pos = -1;
							System.arraycopy(buffer, 0, buf, 0, buf_len = index);
						} else {
							pos = raf.getFilePointer();
						}
						buf_idx = 0;
						return buf_len > 0;
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				}

				void advance() {
					byte v;
					for (int k = v = 0; v >= 0; k += 7) {
						if (buf_idx == buf_len) {
							if (!fill_buf()) {
								next = null;
								return;
							}
						}
						v = buf[buf_idx++];
						BigInteger delta = BigInteger.valueOf(127 & v).shiftLeft(k);
						next = next == null ? delta : next.add(delta);
					}
				}
			};
		}

		@Override
		public String toString() {
			if (size() > 1000) {
				return "[" + getFirst() + ", ... x " + (size() - 2) + " ..., " + getLast() + "]";
			}
			return super.toString();
		}

	}

	/**
	 * Returns true iff this BigInteger passes the specified number of Miller-Rabin
	 * tests. This test is taken from the DSA spec (NIST FIPS 186-2).
	 *
	 * The following assumptions are made: This BigInteger is a positive, odd number
	 * greater than 2. iterations<=50.
	 */
	boolean passesMillerRabin(BigInteger N, int iterations) {
		// Find a and m such that m is odd and this == 1 + 2**a * m
		BigInteger thisMinusOne = N.subtract(ONE);
		BigInteger m_ = thisMinusOne;
		int a = m_.getLowestSetBit();
		BigInteger m = m_.shiftRight(a);

		return IntStream.range(0, iterations).parallel().allMatch(ignored -> {
			Random rnd = ThreadLocalRandom.current();
			// Generate a uniform random on (1, this)
			BigInteger b;
			do {
				b = new BigInteger(N.bitLength(), rnd);
			} while (b.compareTo(ONE) <= 0 || b.compareTo(N) >= 0);

			int j = 0;
			BigInteger z = b.modPow(m, N);
			while (!((j == 0 && z.equals(ONE)) || z.equals(thisMinusOne))) {
				if (j > 0 && z.equals(ONE) || ++j == a)
					return false;
				z = z.modPow(TWO, N);
			}

			return true;
		});
	}

	boolean isPrime(BigInteger N) {
		if (N.testBit(0) == false)
			return N.equals(TWO);

		return passesMillerRabin(N, 100) && N.isProbablePrime(1);
	}

	/**
	 * Threa-safe
	 * 
	 * @param N
	 * @return
	 */
	final BigInteger nextPrime(BigInteger N) {
		++primeCallCount;
		return nextPrimeImpl(N);
	}

	BigInteger nextPrimeImpl(BigInteger N) {
		return N.nextProbablePrime();
	}

	BigInteger prevPrime(BigInteger N) {
		if (isPrime(N))
			return N;

		BigInteger P = N;
		// find *a* prime before N
		for (BigInteger K = v(2); P.compareTo(N) >= 0; K = K.shiftLeft(1)) {
			P = nextPrimeImpl(N.subtract(K));
		}
		// find the *last* one before N
		for (BigInteger Q = nextPrimeImpl(P); Q.compareTo(N) < 0; Q = nextPrimeImpl(Q)) {
			P = Q;
		}
		return P;
	}

	abstract BigInteger find(int gap, BigInteger after);

	// --- Prime discovery rate tracking ---
	long primeCallCount = 0;
	private LongSupplier timer = initTimer();

	private static LongSupplier initTimer() {
		ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
		if (tmx.isThreadCpuTimeSupported()) {
			tmx.setThreadCpuTimeEnabled(true);
			System.err.println("[timer] using thread CPU time");
			return tmx::getCurrentThreadCpuTime;
		}
		System.err.println("[timer] fallback to nanoTime");
		return System::nanoTime;
	}

	void searchGaps() {

		BigInteger P = v(2);
		long total = 0;
		double prev = 1;
		double best_merit = 0;

		for (int gap = 2; gap < 1 << 20; gap += 2) {
			printf("Searching gap >= %s...", gap);

			long time = timer.getAsLong();
			P = find(gap, P);
			time = timer.getAsLong() - time;
			total += time;
			printf("found.\n");

			BigInteger Q = nextPrime(P);
			gap = Q.subtract(P).intValueExact();
			double p = P.doubleValue();
			double merit = gap / Math.log(p);
			String merit_pfx = "";
			if (merit > best_merit) {
				best_merit = merit;
				merit_pfx = "+";
			}

			// Compute average prime discovery rate
			String rateStr = String.format(Locale.ENGLISH, "%,.0f", (primeCallCount*1e9)/total).replace(',',' ');

			printf(">> %d\n + %s\n = %s\n", gap, P, Q);
			printf("%.3fs (tot=%.3fs), %d bits, %d digits, " + "x%.2g prev, %s%.2f merit, ~%g, %s p/s.\n", time / 1e9,
					total / 1e9, P.bitLength(), P.toString().length(), p / prev, merit_pfx, merit, p, rateStr);
			prev = p;
			P = Q;
		}
	}

	void run() {
		searchGaps();
	}

	void run_() {
		Thread t = new Thread(this::searchGaps);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
		try {
			t.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
