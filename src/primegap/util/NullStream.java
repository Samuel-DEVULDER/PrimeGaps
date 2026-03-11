package primegap.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

public class NullStream extends PrintStream {
	NullStream(Runnable writeRunnable) {
		super(new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				writeRunnable.run();
			}
		});
	}

	final public static NullStream instance = of(() -> {
	});

	public static NullStream of(Runnable writeRunnable) {
		return new NullStream(writeRunnable);
	}
}
