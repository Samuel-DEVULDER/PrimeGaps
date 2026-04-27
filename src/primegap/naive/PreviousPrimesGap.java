package primegap.naive;

import java.math.BigInteger;

import primegap.IterativePrimeGap;
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
		if (N.compareTo(primes.getLast()) <= 0) {
			return primes.stream().anyMatch(p -> p.equals(N));
		} else {
			return !primes.stream().filter(p -> p.multiply(p).compareTo(N) <= 0).anyMatch(p -> isDivisibleBy(N, p));
		}
	}

	public static void main(String[] args) {
		new PreviousPrimesGap().run();
	}
}
