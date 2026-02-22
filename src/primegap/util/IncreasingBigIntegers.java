package primegap.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.Cleaner;
import java.math.BigInteger;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;

public class IncreasingBigIntegers extends AbstractCollection<BigInteger> implements Collection<BigInteger> {
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