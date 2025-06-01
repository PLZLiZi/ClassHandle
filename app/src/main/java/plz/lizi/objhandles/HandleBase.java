package plz.lizi.objhandles;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.rmi.RemoteException;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import com.sun.tools.attach.VirtualMachine;
import plz.lizi.objhandles.javassist.ClassPool;
import plz.lizi.objhandles.javassist.CtClass;
import sun.misc.Unsafe;

public final class HandleBase {
	static Unsafe UNSAFE = HandleBase.getUnsafe();
	static Lookup LOOKUP = HandleBase.getLookup();
	static Instrumentation INST;

	public static void initINST() {
		try {
			Field f = Class.forName("sun.tools.attach.HotSpotVirtualMachine").getDeclaredField("ALLOW_ATTACH_SELF");
			HandleBase.UNSAFE.putObject(HandleBase.UNSAFE.staticFieldBase(f), HandleBase.UNSAFE.staticFieldOffset(f), true);
			VirtualMachine vm = VirtualMachine.attach(String.valueOf(ProcessHandle.current().pid()));
			vm.loadAgent(HandleBase.getJarPath());
			vm.detach();
		} catch (Exception e) {
		}
		if (INST == null) {
			System.out.println("[OBJ HANDLE] Can't init INST env");
		}
	}

	public static void initINST(Instrumentation inst) {
		INST = inst;
		if (INST == null) {
			INST = new JVMTIInstrumentation();
		}
	}

	static Unsafe getUnsafe() {
		if (UNSAFE != null)
			return UNSAFE;
		Unsafe instance = null;
		try {
			Constructor<Unsafe> c = Unsafe.class.getDeclaredConstructor();
			c.setAccessible(true);
			instance = c.newInstance();
		} catch (Throwable var3) {
			var3.printStackTrace();
		}
		return instance;
	}

	static MethodHandles.Lookup getLookup() {
		try {
			return (MethodHandles.Lookup) UNSAFE.getObjectVolatile(UNSAFE.staticFieldBase(MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP")), UNSAFE.staticFieldOffset(MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP")));
		} catch (Exception e) {
			try {
				Constructor<MethodHandles.Lookup> c = MethodHandles.Lookup.class.getDeclaredConstructor();
				c.setAccessible(true);
				return c.newInstance();
			} catch (Throwable var3) {
				var3.printStackTrace();
			}
		}
		return null;
	}

	static String realClassName(Class<?> klass) {
		return klass.getName().split("\\$\\$")[0].split("\\$")[0].trim();
	}
	
	static String fromVMName(String vmType) {
		if (vmType == null || vmType.isEmpty()) {
			return vmType;
		}
		switch (vmType) {
			case "I":
				return "int";
			case "F":
				return "float";
			case "J":
				return "long";
			case "D":
				return "double";
			case "Z":
				return "boolean";
			case "B":
				return "byte";
			case "C":
				return "char";
			case "S":
				return "short";
			case "V":
				return "void";
		}
		if (vmType.startsWith("[")) {
			int arrayDepth = 0;
			while (arrayDepth < vmType.length() && vmType.charAt(arrayDepth) == '[') {
				arrayDepth++;
			}
			String elementType = fromVMName(vmType.substring(arrayDepth));
			return elementType + "[]".repeat(arrayDepth);
		}
		if (vmType.startsWith("L") && vmType.endsWith(";")) {
			String className = vmType.substring(1, vmType.length() - 1);
			return className.replace('/', '.');
		}
		return vmType.replace('/', '.');
	}

	static String toVMName(String type) {
		if (type == null || type.isEmpty()) {
			return type;
		}
		switch (type) {
			case "int":
				return "I";
			case "float":
				return "F";
			case "long":
				return "J";
			case "double":
				return "D";
			case "boolean":
				return "Z";
			case "byte":
				return "B";
			case "char":
				return "C";
			case "short":
				return "S";
			case "void":
				return "V";
		}
		if (type.endsWith("[]")) {
			int dimensions = 0;
			while (type.endsWith("[]")) {
				dimensions++;
				type = type.substring(0, type.length() - 2);
			}
			String baseType = toVMName(type);
			return "[".repeat(dimensions) + baseType;
		}
		if (type.contains(".")) {
			return "L" + type.replace('.', '/') + ";";
		}
		return type;
	}

	@SuppressWarnings("unchecked")
	static <T> T copy(T original, boolean deep) throws Throwable {
		if (original == null) {
			return null;
		}
		if (original.getClass().isArray()) {
			int length = java.lang.reflect.Array.getLength(original);
			Object newArray = java.lang.reflect.Array.newInstance(original.getClass().getComponentType(), length);
			System.arraycopy(original, 0, newArray, 0, length);
			return (T) newArray;
		}
		T copy = (T) UNSAFE.allocateInstance(original.getClass());
		HandleBase.copyFields(original, copy);
		return copy;
	}

	static <T> T copy(T original) throws Throwable {
		return copy(original, false);
	}

	static void copyFields(Object old, Object next) {
		copyFields(old, next, false);
	}

	static void copyFields(Object old, Object next, boolean deep) {
		Map<String, Object> oldFieldMap = new HashMap<>();
		for (Field field : old.getClass().getDeclaredFields()) {
			try {
				if (!Modifier.isStatic(field.getModifiers())) {
					oldFieldMap.put(field.getName(), copy(LOOKUP.unreflectGetter(field).invoke(old)));
				}
			} catch (Throwable e) {
			}
		}
		for (Field field : next.getClass().getDeclaredFields()) {
			if (oldFieldMap.containsKey(field.getName())) {
				Object obj = oldFieldMap.get(field.getName());
				try {
					LOOKUP.unreflectSetter(field).invoke(next, obj);
				} catch (Throwable e) {
				}
			}
		}
	}

	static boolean wasLoaded(String name, ClassLoader loader) {
		try {
			for (Class<?> c : new ArrayList<>((ArrayList<Class<?>>) HandleBase.LOOKUP.findVarHandle(ClassLoader.class, "classes", ArrayList.class).get(loader))) {
				if (c.getName().equals(name)) {
					return true;
				}
			}
		} catch (Exception e) {}
		return false;
	}

	static byte[] readAllBytes(InputStream is) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] data = new byte[4096];
		int bytesRead;
		while ((bytesRead = is.read(data, 0, data.length)) != -1) {
			buffer.write(data, 0, bytesRead);
		}
		buffer.flush();
		return buffer.toByteArray();
	}

	static byte[] getClassBytes(Class<?> clazz) throws Exception {
		InputStream is = clazz.getResourceAsStream("/" + realClassName(clazz).replace('.', '/') + ".class");
		byte[] dat = new byte[is.available()];
		is.read(dat);
		is.close();
		return dat;
	}

	static byte[] getClassBytes(String jarPath, String className) throws Exception {
		try (JarFile jarFile = new JarFile(jarPath)) {
			String classPath = className.replace('.', '/') + ".class";
			JarEntry entry = jarFile.getJarEntry(classPath);
			if (entry == null) {
				jarFile.close();
				throw new ClassNotFoundException("Class not found in JAR: " + className);
			}
			try (InputStream is = jarFile.getInputStream(entry)) {
				return readAllBytes(is);
			}
		}
	}

	static String getJarPath() {
		try {
			ProtectionDomain protectionDomain = ClassHandle.class.getProtectionDomain();
			CodeSource codeSource = protectionDomain.getCodeSource();
			if (codeSource != null) {
				URL jarUrl = codeSource.getLocation();
				return new File(jarUrl.getPath()).getAbsolutePath().split("%")[0];
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
		return "";
	}

	static String getJarPath(Class<?> cls) {
		try {
			CodeSource codeSource = cls.getProtectionDomain().getCodeSource();
			if (codeSource != null) {
				return new File(codeSource.getLocation().getPath()).getAbsolutePath().split("%")[0];
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
		return "";
	}

	static String readFile(String filePath) throws Throwable {
		Path path = Paths.get(filePath);
		File file = path.toFile();
		if (!file.exists()) {
			throw new RemoteException("null");
		}
		byte[] encoded = readAllBytes(new FileInputStream(file));
		return new String(encoded, StandardCharsets.UTF_8);
	}

	static void writeFile(String filePath, String content) throws Exception {
		File file = new File(filePath);
		if (!file.exists()) {
			file.createNewFile();
		}
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
			writer.write(content);
		}
	}

	static void accessModule(Module moudle) {
		try {
			LOOKUP.findStatic(Module.class, "addReads0", MethodType.methodType(void.class, Module.class, Module.class)).invoke(moudle, null);
		} catch (Throwable e) {
		}
	}

	static String getStackTrace() {
		StringBuilder builder = new StringBuilder();
		for (StackTraceElement stackTrace : Thread.currentThread().getStackTrace()) {
			builder.append(stackTrace);
			builder.append("\n");
		}
		return builder.toString();
	}

	@SuppressWarnings("removal")
	static void klassPtr(Object o, Class<?> clazz) {
		if (o == null || clazz == null)
			return;
		if (o.getClass().equals(clazz) || clazz.isInstance(o))
			return;
		if (UNSAFE.shouldBeInitialized(clazz)) {
			try {
				int klass_ptr = UNSAFE.getIntVolatile(UNSAFE.allocateInstance(clazz), UNSAFE.addressSize());
				UNSAFE.putIntVolatile(o, UNSAFE.addressSize(), klass_ptr);
			} catch (Throwable ex) {
				ex.printStackTrace();
			}
		}
	}

	static String[] splitFirst(String str, String delimiter) {
		if (str == null || delimiter == null) {
			return new String[] { "", "" };
		}
		int index = str.indexOf(delimiter);
		if (index == -1) {
			return new String[] { str, "" };
		}
		return new String[] { str.substring(0, index), str.substring(index + delimiter.length()) };
	}

	static String[] splitLast(String str, String delimiter) {
		if (str == null || delimiter == null) {
			return new String[] { "", "" };
		}
		int index = str.lastIndexOf(delimiter);
		if (index == -1) {
			return new String[] { str, "" };
		}
		return new String[] { str.substring(0, index), str.substring(index + delimiter.length()) };
	}

	public static void main(String[] args) {
		initINST(null);
	}
}
