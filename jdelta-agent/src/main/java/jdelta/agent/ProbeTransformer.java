package jdelta.agent;

import jdelta.runtime.Recorder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * project output directory에서 load된 class의 모든 method 시작에 {@code Recorder.hit(index)}를 넣는다 (§8.2).
 * library, JDK, Gradle worker class는 건드리지 않는다.
 */
final class ProbeTransformer implements ClassFileTransformer {
    private static final String RECORDER = "jdelta/runtime/Recorder";

    private final List<AgentConfig.OutputDir> dirs;
    /** code source location -> module. 매 class마다 경로를 다시 비교하지 않는다. */
    private final Map<String, Optional<String>> moduleByLocation = new ConcurrentHashMap<>();

    ProbeTransformer(List<AgentConfig.OutputDir> dirs) {
        this.dirs = dirs;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain domain, byte[] bytes) {
        if (className == null || classBeingRedefined != null || domain == null || className.startsWith("jdelta/")) return null;
        String module = moduleOf(domain.getCodeSource());
        if (module == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            if ((reader.getAccess() & Opcodes.ACC_MODULE) != 0) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ProbeClassVisitor(writer, module + "|" + reader.getClassName()), 0);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            // instrument 실패가 test를 깨뜨리면 안 된다. 그 class는 trace에서 빠진다.
            System.err.println("jdelta: cannot instrument " + className + ": " + e);
            return null;
        }
    }

    private String moduleOf(CodeSource source) {
        if (source == null || source.getLocation() == null) return null;
        String location = source.getLocation().toString();
        return moduleByLocation.computeIfAbsent(location, l -> {
            try {
                Path path = AgentConfig.normalize(Path.of(source.getLocation().toURI()));
                return dirs.stream().filter(d -> path.startsWith(d.path())).map(AgentConfig.OutputDir::module).findFirst();
            } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static final class ProbeClassVisitor extends ClassVisitor {
        private final String classId;

        ProbeClassVisitor(ClassVisitor next, String classId) {
            super(Opcodes.ASM9, next);
            this.classId = classId;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return next;
            int index = Recorder.register(classId + "#" + name + descriptor);
            return new MethodVisitor(Opcodes.ASM9, next) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    // constructor에서도 super() 전에 static call은 허용된다(this를 쓰지 않으므로)
                    pushInt(this, index);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "hit", "(I)V", false);
                }
            };
        }

        private static void pushInt(MethodVisitor mv, int value) {
            if (value <= 5) mv.visitInsn(Opcodes.ICONST_0 + value);
            else if (value <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, value);
            else if (value <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, value);
            else mv.visitLdcInsn(value);
        }
    }
}
