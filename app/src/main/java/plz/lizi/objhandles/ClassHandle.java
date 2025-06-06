/*
 * This source file was generated by the Gradle 'init' task
 */
package plz.lizi.objhandles;

import static plz.lizi.objhandles.HandleBase.*;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public final class ClassHandle {
	private Class<?> klass;
	private byte[] bytes;
	private String handleName;
	private String className;
	private ClassLoader loader;
	private static Map<String, Class<?>> hiddenClassMap = new HashMap<>();

	public ClassHandle(Class<?> klass) {
		this.klass = klass;
		this.handleName = klass.getName();
		this.className = handleName;
		this.loader = klass.getClassLoader();
	}

	public ClassHandle(String name, ClassLoader loader, Class<?> lookup) {
		try {
			if (loader == null)
				loader = lookup.getClassLoader();
			if (wasLoaded(name, loader)) {
				this.klass = Class.forName(name, false, loader);
				this.handleName = klass.getName();
				this.className = klass.getName();
				this.loader = klass.getClassLoader();
				this.bytes = HandleBase.getClassBytes(klass);
			} else {
				this.handleName = name;
				this.className = name;
				this.loader = loader;
				this.bytes = HandleBase.getClassBytes(HandleBase.getJarPath(lookup), name);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public ClassHandle(String name, ClassLoader loader, byte[] bytes) {
		try {
			if (wasLoaded(name, loader)) {
				this.klass = Class.forName(name, false, loader);
				this.handleName = klass.getName();
				this.className = klass.getName();
				this.loader = klass.getClassLoader();
				this.bytes = HandleBase.getClassBytes(klass);
			} else {
				this.handleName = name;
				this.className = name;
				this.loader = loader;
				this.bytes = bytes;
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public void redefine(byte[] newBuf) throws Throwable {
		HandleBase.INST.redefineClasses(new ClassDefinition(klass, newBuf));
	}

	public void retransform(byte[] newBuf) throws Throwable {
		ClassFileTransformer transformer = new ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
				if (className.equals(ClassHandle.this.className)) {
					return newBuf;
				}
				return classfileBuffer;
			}

			@Override
			public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
				if (className.equals(ClassHandle.this.className)) {
					return newBuf;
				}
				return classfileBuffer;
			}
		};
		HandleBase.INST.addTransformer(transformer, true);
		HandleBase.INST.retransformClasses(klass);
		HandleBase.INST.removeTransformer(transformer);
	}

	public static Class<?> findHidden(String name) {
		return hiddenClassMap.get(name);
	}

	public Class<?> define() throws Throwable {
		if (klass != null) {
			return klass;
		}
		klass = (Class<?>) HandleBase.LOOKUP.findVirtual(ClassLoader.class, "defineClass", MethodType.methodType(Class.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class)).invoke(loader, className, bytes, 0, bytes.length, null);
		setClassName(className);
		return klass;
	}

	public Class<?> defineHidden() throws Throwable {
		if (klass != null) {
			return klass;
		}
		if (hiddenClassMap.containsKey(handleName) && hiddenClassMap.get(handleName) != null) {
			return hiddenClassMap.get(handleName);
		}
		int flags = 6;
		if (loader == null || loader == ClassLoader.getPlatformClassLoader()) {
			flags |= 8;
		}
		klass = (Class<?>) HandleBase.LOOKUP.findStatic(ClassLoader.class, "defineClass0", MethodType.methodType(Class.class, ClassLoader.class, Class.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class, boolean.class, int.class, Object.class)).invoke(loader, ClassHandle.class, className, bytes, 0, bytes.length, null, true, flags, null);
		setClassName(className);
		hiddenClassMap.put(handleName, klass);
		return klass;
	}

	public ClassLoader getLoader() {
		return loader;
	}

	public ClassHandle setLoader(ClassLoader loader) {
		this.loader = loader;
		return this;
	}

	public byte[] getBytes() {
		if (bytes == null) {
			try {
				bytes = HandleBase.getClassBytes(klass);
			} catch (Exception e) {}
		}
		return bytes;
	}

	public ClassHandle setBytes(byte[] bytes) {
		this.bytes = bytes;
		return this;
	}

	public ClassHandle setClassName(String newName) throws Throwable {
		className = newName;
		if (klass != null)
			HandleBase.LOOKUP.findSetter(Class.class, "name", String.class).invoke(klass, className);
		return this;
	}

	public ClassHandle setHandleName(String newName) {
		this.handleName = newName;
		return this;
	}

	public String getHandleName() {
		return handleName;
	}

	public String getClassName() {
		return className;
	}

	public Class<?> toClass() {
		return klass;
	}

	public Object getStaticField(String fname) throws Throwable {
		Field f = klass.getDeclaredField(fname);
		try {
			return f.get(null);
		} catch (Exception e) {
			try {
				return LOOKUP.unreflectGetter(f).invoke();
			} catch (Throwable e1) {
				try {
					return UNSAFE.getObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f));
				} catch (Exception e2) {
					e2.initCause(e);
					e2.initCause(e1);
					throw e2;
				}
			}
		}
	}

	public void setStaticField(String fname, Object o) throws Throwable {
		Field f = klass.getDeclaredField(fname);
		try {
			f.set(null, o);
		} catch (Throwable e) {
			try {
				LOOKUP.unreflectSetter(f).invoke(o);
			} catch (Throwable e1) {
				try {
					UNSAFE.putObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f), o);
				} catch (Throwable e2) {
					e2.initCause(e);
					e2.initCause(e1);
					throw e2;
				}
			}
		}
	}

	public Object invokeStaticMethod(String mname, MethodType type, Object... args) throws Throwable {
		Method m = klass.getDeclaredMethod(mname, type.parameterArray());
		try {
			return m.invoke(null, args);
		} catch (Throwable e) {
			try {
				return LOOKUP.unreflect(m).invoke(args);
			} catch (Throwable e1) {
				e1.initCause(e);
				throw e1;
			}
		}
	}
}
