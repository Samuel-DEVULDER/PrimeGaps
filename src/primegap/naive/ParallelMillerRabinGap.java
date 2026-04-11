package primegap.naive;

import java.math.BigInteger;

import primegap.JavaNextProbablePrimeGap;

public class ParallelMillerRabinGap extends JavaNextProbablePrimeGap {
	@Override
	protected BigInteger nextPrimeImpl(BigInteger N) {
		BigInteger P = N.add(N.testBit(0) ? TWO : ONE);
		while (!isPrime(P))
			P = P.add(TWO);
		return P;
	}

	public static void main(String[] args) throws Exception {
		new ParallelMillerRabinGap().run();
	}
}
