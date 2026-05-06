package primegap.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.Cleaner;
import java.lang.ref.SoftReference;
import java.math.BigInteger;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * This class implements a collection of increasing BigIntegers that are stored
 * in a temporary file on disk. The integers are stored as deltas from the
 * previous integer, using a variable-length encoding to save space. The class
 * provides methods to add integers to the collection and to iterate over the
 * integers in order.
 * 
 * The implementation uses a cleaner to ensure that the temporary file is
 * deleted when the collection is no longer in use, and also provides a
 * dispose() method for manual cleanup.
 * 
 * The collection is designed to handle a large number of integers without
 * consuming a lot of memory, making it suitable for applications that need to
 * store and process large sets of integers that may not fit in memory.
 */
public class IncreasingBigIntegers extends AbstractCollection<BigInteger> implements Collection<BigInteger> {
	private static final Cleaner CLEANER = Cleaner.create();

	private static class CleanupState implements Runnable {
		private File f;
		private RandomAccessFile raf;

		CleanupState(File f, RandomAccessFile raf) {
			this.raf = raf;
			this.f = f;
			Java.atexit(this); // add
		}

		@Override
		public void run() {
			Java.atexit(this); // remove
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
				assert Java.dbg("Temp file cleaned up: ", loc2);
				f = null;
			}
		}
	}

	final File dbFile;
	final RandomAccessFile raf;

	final byte[] buffer;
	int index;

	BigInteger last, first;
	final long LIMIT;
	long size;

	public BigInteger getFirst() {
		return first;
	}

	public BigInteger getLast() {
		return last;
	}

	public IncreasingBigIntegers(int size, long limit) {
		try {
			dbFile = File.createTempFile("gap", ".primes");
			dbFile.deleteOnExit();
			assert Java.dbg(dbFile);
			raf = new RandomAccessFile(dbFile, "rw");
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
		this.LIMIT = limit <= 0 ? Integer.MAX_VALUE : limit;
		buffer = new byte[size];
		size = index = 0;

		var cl = new CleanupState(dbFile, raf);
		CLEANER.register(this, cl);
	}

	public IncreasingBigIntegers(int size) {
		this(size, Integer.MAX_VALUE);
	}

	public IncreasingBigIntegers(Collection<BigInteger> col) throws IOException {
		this(4096);
		col.stream().sorted().distinct().forEach(this::add);
	}

	public void dispose() {
		RandomAccessFile loc = raf;
		if (loc != null) {
			try {
				loc.close();
			} catch (IOException e) {
			}
		}
		File loc2 = dbFile;
		if (loc2 != null && loc2.delete()) {
			loc2.delete();
			assert Java.dbg("Temp file deleted: " + loc2);
		}
	}

	@Override
	public int size() {
		return (int) Long.min(Integer.MAX_VALUE, size);
	}

	public long sizeLong() {
		return size;
	}

	public boolean isFull() {
		return size == LIMIT;
	}

	static final BigInteger ONE_TWO_SEVEN = BigInteger.valueOf(127);

	void pushByte(int b) throws IOException {
		buffer[index++] = (byte) b;
		if (index == buffer.length) {
			synchronized (raf) {
				raf.seek(raf.length());
				raf.write(buffer, 0, index);
			}
			index = 0;
		}
	}

	void push(BigInteger n) throws IOException {
		int len = n.bitLength();
		if (len <= 7) {
			pushByte(n.byteValue() + 128);
		} else if (len <= 64) {
			long v = n.longValue();
			while ((v & ~127L) != 0L) {
				pushByte((int) (v & 127));
				v >>>= 7;
			}
			pushByte(128 + (int) v);
		} else {
			while (n.compareTo(ONE_TWO_SEVEN) > 0) {
				pushByte(n.byteValue() & 127);
				n = n.shiftRight(7);
			}
			pushByte(n.byteValue() + 128);
		}
	}

	@Override
	public boolean add(BigInteger e) {
		if (isFull())
			return false;
		BigInteger delta = last == null ? e : e.subtract(last);
		if (delta.signum() <= 0)
			return false;
		++size;
		if (first == null)
			first = e;
		last = e;
		try {
			push(delta);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
		return true;
	}

	@Override
	public Iterator<BigInteger> iterator() {
		return new Itr();
	}

	class Itr implements Iterator<BigInteger> {
		Itr() {
			advance();
		}

		Itr(State state) {
			this.next = state.next;
			this.pos = state.pos & Long.MAX_VALUE;
			fill_buf();
			this.buf_idx = state.buf_idx;
			advance();
		}

		byte[] buf = new byte[buffer.length];
		BigInteger next, nonnull_next;
		long pos = 0;
		int buf_len, buf_idx;

		public static record State(BigInteger next, long pos, int buf_idx) {

		}

		public State getState() {
			return new State(nonnull_next, pos, buf_idx);
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

		protected boolean cannot_fill() {
			return pos < 0;
		}

		protected void disable_fill() {
			pos |= Long.MIN_VALUE;
		}

		boolean fill_buf() {
			try {
				if (cannot_fill()) {
					return false;
				}
				synchronized (raf) {
					raf.seek(pos);
					buf_len = raf.read(buf);
					if (buf_len < 0) {
						disable_fill();
						System.arraycopy(buffer, 0, buf, 0, buf_len = index);
					} else {
						pos = raf.getFilePointer();
					}
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
						nonnull_next = next;
						next = null;
						return;
					}
				}
				v = buf[buf_idx++];
				BigInteger delta = BigInteger.valueOf(127 & v).shiftLeft(k);
				nonnull_next = next = next == null ? delta : next.add(delta);
			}
		}
	}

	@Override
	public String toString() {
		if (size() > 1000) {
			return "[" + getFirst() + ", ... x " + (size() - 2) + " ..., " + getLast() + "]";
		}
		return super.toString();
	}

	public long limit() {
		return LIMIT;
	}

	public static BigInteger[] EMPTY = new BigInteger[0];
	class LimitedSubCollection {
		SoftReference<BigInteger[]> collected = new SoftReference<BigInteger[]>(EMPTY);

		Itr.State state = null;

		int limit_idx;

		protected int findIndexGT(BigInteger[] array, int from, BigInteger limit) {
			if (from == array.length || limit.compareTo(array[from]) < 0)
				return from;
			++from;
			if (from == array.length || limit.compareTo(array[from]) < 0)
				return from;

			int a = from, b = array.length;
			while (b - a > 1) {
				int c = a + (b - a) / 2;
				int d = array[c].compareTo(limit);
				if (d <= 0)
					a = c;
				else
					b = c;
			}
			return b;
		}

		public Collection<BigInteger> collectUpTo(BigInteger limit_included) {
			if (collected != null)
				try {
					var array = collected.get();
					if (array == null) {
						array = EMPTY;
						state = null;
						limit_idx = 0;
					}
					if (array.length == 0 || limit_included.compareTo(array[array.length - 1]) > 0) {
						List<BigInteger> compl = new ArrayList<>();

						Itr itr = state == null ? new Itr() : new Itr(state);

						while (itr.hasNext()) {
							compl.add(itr.next());
							if (last.compareTo(limit_included) > 0) {
								itr.disable_fill();
							}
						}
						state = itr.getState();

						int len = array.length;
						array = Arrays.copyOf(array, len + compl.size());
						for (BigInteger b : compl)
							array[len++] = b;
						compl.clear();
						collected = new SoftReference<BigInteger[]>(array);
					}
					limit_idx = findIndexGT(array, limit_idx, limit_included);
					var final_array = array;
					var final_size = limit_idx;
					var ret = new AbstractList<BigInteger>() {
						@Override
						public int size() {
							return final_size;
						}

						@SuppressWarnings("unchecked")
						@Override
						public <T> T[] toArray(T[] a) {
							if (a == EMPTY)
								return (T[]) final_array;
							return super.toArray(a);
						}

						@Override
						public Object[] toArray() {
							return final_array;
						}

						@Override
						public BigInteger get(int index) {
							return final_array[index];
						}
					};
					return ret;
				} catch (OutOfMemoryError oem) {
					collected = null;
				}

			return new AbstractCollection<BigInteger>() {
				int size = -1;

				@Override
				public Iterator<BigInteger> iterator() {
					return new Itr() {
						@Override
						public boolean hasNext() {
							return super.hasNext() && next.compareTo(limit_included) <= 0;
						}
					};
				}

				@Override
				public int size() {
					int n = size;
					if (n < 0) {
						n = 0;
						for (Iterator<BigInteger> i = iterator(); i.hasNext() && n < Integer.MAX_VALUE; i.next())
							++n;
						size = n;
					}
					return n;
				}
			};
		}
	}

	LimitedSubCollection subCol = new LimitedSubCollection();

	synchronized public Collection<BigInteger> upTo(BigInteger limit_included) {
		return isEmpty() ? Collections.emptyList() : subCol.collectUpTo(limit_included);
	}

}