package primegap.naive;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorOperators.Comparison;
import jdk.incubator.vector.VectorSpecies;
import primegap.util.Java;

public class SIMDGap extends ParallelMillerRabinGap {
	
	/**
	 * WheelBig 210 generates BigInteger candidates using wheel factorization with
	 * SIMD filtering on small primes.
	 *
	 * Forward/backward iteration is supported. Subclasses implement advance() to
	 * avoid testing forward in hot loop.
	 *
	 * avg tracks the average delta observed (can be used for statistics).
	 */
	static public abstract class Wheel implements Supplier<BigInteger> {
		Comparison UNSIGNED_GE = Java.SIMD.UNSIGNED_GE;
		
		// ================= STATIC WHEEL =================
		protected static final int WHEEL_SIZE;
		protected static final List<Integer> COPRIMES;
		protected static final int NUM_STEPS;
		protected static final int MAX_INCREMENT;
		protected static final int[] STEPS;

		// ================= SIMD SMALL PRIMES =================
		protected static final VectorSpecies<Byte> B_SPEC = ByteVector.SPECIES_512;

		protected static final ByteVector B_PRIMES;
		protected static final BigInteger WHEEL_THRESHOLD;

		static {
			COPRIMES = new ArrayList<>();
			int maxInc = 0, wheel, steps[];
			double ratio;

			List<Integer> primes = new ArrayList<>();

			// find all primes fitting in a byte after increment
			// (to compute remainders without overflow)
			do {
				// SIMD primes
				primes.clear();
				int p = 2;
				ratio = 0.5;
				while (p + maxInc <= 255) {
					primes.add(p);
					p = BigInteger.valueOf(p).nextProbablePrime().intValue();
				}

				wheel = primes.removeFirst();
				while (primes.size() > B_SPEC.length()) {
					BigInteger w = BigInteger.valueOf(wheel);
					BigInteger t = BigInteger.valueOf(primes.getFirst());
					w = w.multiply(t);
					if (w.bitLength() <= 16) { // avoid too big wheels
						wheel = w.intValue();
						ratio *= (1 - 1 / primes.removeFirst().doubleValue());
					} else
						break;
				}
				while (primes.size() > B_SPEC.length()) {
					primes.removeLast();
				}

				COPRIMES.clear();
				for (int i = 1; i < wheel; ++i)
					if (gcd(i, wheel) == 1)
						COPRIMES.add(i);

				// steps between coprimes
				steps = new int[COPRIMES.size()];
				for (int i = 0; i < steps.length; ++i) {
					int next = COPRIMES.get((i + 1) % steps.length);
					int step = (next - COPRIMES.get(i) + wheel) % wheel;
					if (step == 0)
						step = wheel; // degenerate case
					steps[i] = step;
					if (step > maxInc)
						maxInc = step;
				}
			} while (primes.getLast() + maxInc > 255);

			for (var p : primes)
				ratio *= (1 - 1 / p.doubleValue());
			System.err.println("filtering probability=" + ratio);
			System.err.println("avg from probability=" + 1 / ratio);

			// degenerate case
			if (wheel == 1) {
				steps = new int[] { 2 };
				maxInc = 2;
			}

			WHEEL_SIZE = wheel;
			STEPS = steps;
			NUM_STEPS = steps.length;
			MAX_INCREMENT = maxInc;
			WHEEL_THRESHOLD = BigInteger.valueOf(primes.getLast());

			byte[] bArr = new byte[B_SPEC.length()];
			for (int i = 0; i < B_SPEC.length(); ++i) {
				int val = i < primes.size() ? primes.get(i) : primes.getLast();
				bArr[i] = (val + MAX_INCREMENT <= 255) ? (byte) val : 2;
			}
			B_PRIMES = ByteVector.fromArray(B_SPEC, bArr, 0);
		}

		// ================= INSTANCE STATE =================
		protected BigInteger P;
		protected int steps_idx;
		protected int delta;
		protected ByteVector bVec;
		protected long cnt;
		protected double avg, avg2; // running average of delta

		// ================= CONSTRUCTOR =================
		protected Wheel(BigInteger start) {
			if (start == null)
				throw new IllegalArgumentException("start cannot be null");

			if (start.compareTo(WHEEL_THRESHOLD) <= 0)
				throw new IllegalArgumentException("start must be > " + WHEEL_THRESHOLD);

			this.P = start;

			int rWheel = P.mod(BigInteger.valueOf(WHEEL_SIZE)).intValue();
			int idx = 0;
			while (idx < NUM_STEPS && COPRIMES.get(idx) < rWheel)
				++idx;

			if (idx == NUM_STEPS) {
				// wrap to next wheel cycle
				delta = (int) (WHEEL_SIZE - rWheel + 1);
				idx = 0;
			} else {
				delta = COPRIMES.get(idx) - rWheel;
			}
			steps_idx = idx;

			byte[] bInit = new byte[B_SPEC.length()];
			for (int i = 0; i < B_SPEC.length(); ++i)
				bInit[i] = P.remainder(BigInteger.valueOf(B_PRIMES.lane(i) & 0xFFL)).byteValue();

			bVec = ByteVector.fromArray(B_SPEC, bInit, 0);

			if (delta != 0)
				updateVectors(delta);
			else
				System.err.println();
		}

		// ================= ABSTRACT ADVANCE =================
		protected abstract void advance();

		protected void updateVectors(int increment) {
			bVec = bVec.add((byte) increment);
			bVec = bVec.sub(B_PRIMES, bVec.compare(UNSIGNED_GE, B_PRIMES));
		}

		protected boolean isComposite() {
			return bVec.compare(VectorOperators.EQ, (byte) 0).anyTrue();
		}

		protected static int gcd(int a, int b) {
			while (b != 0) {
				int t = b;
				b = a % b;
				a = t;
			}
			return a;
		}

		static long start = System.currentTimeMillis();
		static long timeout = start + 5 * 60_000;

		@Override
		public BigInteger get() {
			while (isComposite())
				advance();
			P = P.add(BigInteger.valueOf(delta));
			if (cnt % 100000 == 0) {
				long t = System.currentTimeMillis();
				if (t > timeout) {
					timeout = t + 5 * 60_000;
					System.err.println("tim=" + wdhm((t - start) / 1_000) + " cnt=" + cnt + " avg=" + avg + " avg2="
							+ avg2 + " P=" + P + " " + P.isProbablePrime(100));
				}
			}
			avg = (avg * cnt + delta) / (cnt + 1);
			double alpha = 1.0 / 1000000;
			avg2 = (1 - alpha) * avg2 + alpha * delta;
			++cnt;
			delta = 0;
			advance(); // prepare next candidate
			return P;
		}
	}

// ================= SUBCLASSES =================
	static public class ForwardBranch extends Wheel {
		public ForwardBranch(BigInteger start) {
			super(start);
		}

		@Override
		protected void advance() {
			int increment = STEPS[steps_idx];
			++steps_idx;
			if (steps_idx == NUM_STEPS)
				steps_idx = 0;
			delta += increment;
			updateVectors(increment);
		}
	}

	static public class BackwardBranch extends Wheel {
		public BackwardBranch(BigInteger start) {
			super(start);
		}

		@Override
		protected void advance() {
			--steps_idx;
			if (steps_idx < 0)
				steps_idx = NUM_STEPS - 1;
			int increment = -STEPS[steps_idx];
			delta += increment;
			updateVectors(increment);
		}
	}

	static public class ForwardModulo extends Wheel {
		public ForwardModulo(BigInteger start) {
			super(start);
		}

		@Override
		protected void advance() {
			int increment = STEPS[steps_idx];
			steps_idx = (steps_idx + 1) % NUM_STEPS;
			delta += increment;
			updateVectors(increment);
		}
	}

	static public class BackwardModulo extends Wheel {
		public BackwardModulo(BigInteger start) {
			super(start);
		}

		@Override
		protected void advance() {
			int increment = -STEPS[(steps_idx + NUM_STEPS - 1) % NUM_STEPS];
			steps_idx = (steps_idx + NUM_STEPS - 1) % NUM_STEPS;
			delta += increment;
			updateVectors(increment);
		}
	}

	ForwardBranch prevSupplier;

	Supplier<BigInteger> candidates(BigInteger N) {
		if (prevSupplier != null && prevSupplier.P.equals(N))
			return prevSupplier;
		return prevSupplier = new ForwardBranch(N);
	}

	@Override
	protected BigInteger nextPrimeImpl(BigInteger N) {
		if (N.compareTo(Wheel.WHEEL_THRESHOLD) <= 0)
			return super.nextPrimeImpl(N);
		return Stream.generate(candidates(N))//
				.filter(this::isPrime)//
				.filter(p -> p.compareTo(N) > 0)//
				.findFirst()//
				.orElseThrow();
	}

	public static void main(String[] args) throws Exception {
		new SIMDGap().run();
	}
}
