package primegap.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * A PrintStream that discards all output. It can be used to suppress output
 * from code that writes to System.out or System.err, while still allowing for a
 * callback to check for stopping conditions.
 */
public class NullStream extends PrintStream {
	NullStream(Runnable writeRunnable) {
		super(new OutputStream() {
			@Override
			public void flush() throws IOException {
				super.flush();
				writeRunnable.run();
			}
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
