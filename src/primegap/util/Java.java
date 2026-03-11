package primegap.util;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public class Java {
	public static <E> Class<? extends E>[] findSubclasses(Class<E> parent) {
		String cp = System.getProperty("java.class.path");
		List<Class<? extends E>> result = new ArrayList<>();
		try {
			for (String entry : cp.split(File.pathSeparator)) {
				Path path = Path.of(entry);
				if (Files.isDirectory(path)) {
					try (var stream = Files.walk(path)) {
						stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
							String className = path.relativize(p).toString().replace(File.separatorChar, '.')
									.replace(".class", "");
							tryLoad(className, parent, result);
						});
					}
				} else if (entry.endsWith(".jar")) {
					try (JarFile jar = new JarFile(path.toFile())) {
						jar.entries().asIterator().forEachRemaining(entry2 -> {
							String name = entry2.getName();
							if (name.endsWith(".class")) {
								tryLoad(name.replace('/', '.').replace(".class", ""), parent, result);
							}
						});
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		@SuppressWarnings({ "unchecked", "rawtypes" })
		Class<E>[] classes = result.stream().map(c -> ((Class) c).asSubclass(parent)).toArray(Class[]::new);

		return classes;
	}

	static <E> void tryLoad(String className, Class<E> parent, List<Class<? extends E>> result) {
		if (!className.startsWith("attic.")) {
			try {
				Class<?> cls = Class.forName(className);
				if (parent.isAssignableFrom(cls) && !Modifier.isAbstract(cls.getModifiers())) {
					result.add(cls.asSubclass(parent));
				}
			} catch (Throwable ignored) {
			}
		}
	}
}
