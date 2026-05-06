package primegap.naive;

import java.math.BigInteger;
import java.util.Collection;

import primegap.util.IncreasingBigIntegers;
import primegap.util.Java;

public class PreviousPrimesGap extends SIMDWheelGap {
	static boolean enabled = Java.SIMD.enable();
	final IncreasingBigIntegers primes;

	public PreviousPrimesGap() {
		primes = new IncreasingBigIntegers(1 << 24);
		primes.add(TWO);
	}

	public boolean isDivisibleBy(BigInteger P, BigInteger Q) {
		if (P.bitLength() < 64 && Q.bitLength() < 64) {
			return (P.longValue() % Q.longValue()) == 0L;
		} else {
			return P.remainder(Q).signum() == 0;
		}
	}

	@Override
	public boolean isPrime(BigInteger N) {
		int cmp = N.compareTo(primes.getLast());
		if (cmp == 0) {
			return true;
		} else if (cmp < 0) {
			return primes.stream().anyMatch(p -> p.equals(N));
		} else {
			Collection<BigInteger> list = primes.upTo(N.sqrt());
			var stream = list.stream();
			var ok = !stream.anyMatch(p -> isDivisibleBy(N, p));
			if (ok)
				primes.add(N);
			return ok;
		}
	}

	public static void main(String[] args) {
		new PreviousPrimesGap().run();
	}
}
