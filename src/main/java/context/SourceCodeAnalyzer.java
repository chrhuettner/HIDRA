package context;

import core.JarDiffUtil;
import dto.FileSearchResult;
import org.objectweb.asm.*;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SourceCodeAnalyzer {

    private String sourceDirectory;
    private CtModel model;

    private static ConcurrentHashMap<Long, ConcurrentHashMap<String, SourceCodeAnalyzer>> concurrentInstances;

    static {
        concurrentInstances = new ConcurrentHashMap<>();
    }

    public static SourceCodeAnalyzer getInstance(String sourceDirectory) {
        return getLazyLoadedInstance(sourceDirectory);
    }

    public static void removeCachedAnalysersForThread() {
        long id = Thread.currentThread().threadId();
        System.out.println("THREAD WITH ID "+id+" RELEASES SOURCECODEANALYZER CACHE");
        System.out.println("GLOBAL SOURCECODEANALYZER CACHE SIZE BEFORE: "+concurrentInstances.keySet().size());
        concurrentInstances.remove(id);
        System.out.println("GLOBAL SOURCECODEANALYZER CACHE SIZE AFTER: "+concurrentInstances.keySet().size());
    }

    private static SourceCodeAnalyzer getLazyLoadedInstance(String sourceDirectory) {
        long id = Thread.currentThread().threadId();

        if (!concurrentInstances.containsKey(id)) {
            ConcurrentHashMap<String, SourceCodeAnalyzer> innerMap = new ConcurrentHashMap<>();
            concurrentInstances.put(id, innerMap);
        }

        if (!concurrentInstances.get(id).containsKey(sourceDirectory)) {
            SourceCodeAnalyzer instance = new SourceCodeAnalyzer(sourceDirectory);
            concurrentInstances.get(id).put(sourceDirectory, instance);
        }

        return concurrentInstances.get(id).get(sourceDirectory);

    }

    private SourceCodeAnalyzer(String sourceDirectory) {
        try {
            this.sourceDirectory = sourceDirectory;
            Launcher launcher = new Launcher();

            launcher.addInputResource(sourceDirectory);
            launcher.getEnvironment().setComplianceLevel(11);

            launcher.getEnvironment().setNoClasspath(true);
            launcher.getFactory().getEnvironment().setIgnoreDuplicateDeclarations(true);

            launcher.buildModel();

            model = launcher.getModel();
        } catch (Exception e) {
            System.err.println(sourceDirectory);
            e.printStackTrace();
        }
    }

    public String getReturnTypeOfMethodFromSourceCode(String className, String methodName) {
        if (model == null || className == null ||  methodName == null) {
            return null;
        }

        boolean onlyCheckSuffix = false;
        if (!className.contains(".")) {
            onlyCheckSuffix = true;
        }
        for (CtType<?> type : model.getAllTypes()) {
            //System.out.println("QUAL: "+type.getQualifiedName());
            //System.out.println("SIMPL: "+type.getSimpleName());
            if (type.getQualifiedName().equals(className) || (onlyCheckSuffix && type.getSimpleName().equals(className))) {

                for (CtMethod method : type.getMethods()) {
                    //System.out.println("M SIMPL: "+method.getSimpleName());
                    if (method.getSimpleName().equals(methodName)) {
                        CtTypeReference<?> returnType = method.getType();
                        return returnType.getSimpleName();
                    }

                }
            }
        }

        return null;
    }

    public String getTypeOfFieldFromSourceCode(String className, String fieldName) {
        if (model == null || className == null ||  fieldName == null) {
            return null;
        }

        boolean onlyCheckSuffix = false;
        if (!className.contains(".")) {
            onlyCheckSuffix = true;
        }
        for (CtType<?> type : model.getAllTypes()) {
            //System.out.println("QUAL: "+type.getQualifiedName());
            //System.out.println("SIMPL: "+type.getSimpleName());
            if (type.getQualifiedName().equals(className) || (onlyCheckSuffix && type.getSimpleName().equals(className))) {
                CtField field = type.getField(fieldName);
                return field == null ? null : type.getField(fieldName).getType().getSimpleName();
            }
        }

        return null;
    }

    public String getTypeOfFieldInClass(File directory, String className, String fieldName) {
        if(className == null || fieldName == null) {
            return null;
        }
        final String[] typeOfField = new String[1];

        className = className.replace(".", "/");

        File[] jarFiles = directory.listFiles(f -> f.getName().endsWith(".jar"));

        if(jarFiles == null){
            System.err.println(directory.getAbsolutePath());
        }
        for (File file : jarFiles) {
            try (JarFile jarFile = new JarFile(file)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    //System.out.println(entry.getName());
                    if (entry.getName().endsWith(className + ".class")) {
                        try (InputStream is = jarFile.getInputStream(entry)) {
                            ClassReader reader = new ClassReader(is);
                            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                                    if (name.equals(fieldName)) {
                                        typeOfField[0] = Type.getType(descriptor).getClassName();
                                    }
                                    return super.visitField(access, name, descriptor, signature, value);
                                }
                            }, 0);
                            if (typeOfField[0] != null) {
                                return typeOfField[0];
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }

        return null;
    }

    public FileSearchResult getDependencyFileContainingClass(String className) {
        File depDir = new File(sourceDirectory + "/tmp/dependencies");
        String transformedClassName = className.replace(".", "/");

        FileSearchResult result = getFileEndingWithSuffix(transformedClassName + ".class", depDir, true);

        if (result == null) {
            String shortenedClassName = className.substring(className.lastIndexOf('.') + 1);
            result = getFileEndingWithSuffix(shortenedClassName + ".class", depDir, true);
        }

        return result;
    }

    private FileSearchResult getFileEndingWithSuffix(String suffix, File directory, boolean ignoreCase) {
        File[] jarFiles = directory.listFiles(f -> f.getName().endsWith(".jar"));
        try {
            for (File file : jarFiles) {
                try (JarFile jarFile = new JarFile(file)) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        //System.out.println(entry.getName());
                        if (entry.getName().endsWith(suffix) || (ignoreCase && entry.getName().toUpperCase().endsWith(suffix.toUpperCase()))) {
                            return new FileSearchResult(file, entry);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    public String getReturnTypeOfMethod(String className, String methodName, String[] parameterTypeNames) {
        String returnTypeInSourceCode = getReturnTypeOfMethodFromSourceCode(className, methodName);

        if (returnTypeInSourceCode != null) {
            return returnTypeInSourceCode;
        }

        File depDir = new File(Path.of(sourceDirectory).resolve("tmp").resolve("dependencies").toString());
        String dependencyReturnType = getReturnTypeOfMethodFromDependencies(className, methodName, parameterTypeNames, depDir);

        if (dependencyReturnType != null) {
            return dependencyReturnType;
        }

        File natDir = new File("Java_Src");

        String nativeJavaClassReturnType = getReturnTypeOfMethodFromDependencies(className, methodName, parameterTypeNames, natDir);

        if (nativeJavaClassReturnType != null) {
            return nativeJavaClassReturnType;
        }

        return null;
    }



    public String getReturnTypeOfMethodFromDependencies(String className, String methodName, String[] parameterTypes, File directory) {
        if(className == null || methodName == null){
            return null;
        }

        final String[] returnType = new String[1];

        final String[] returnTypeOfMethodWithSameNumberOfParameters = new String[1];
        String prefix = "";


        className = className.replace(".", "/");
        if(!className.contains("/")){
            prefix = "/";
        }

        File[] jarFiles = directory.listFiles(f -> f.getName().endsWith(".jar"));
        for (File file : jarFiles) {
            try (JarFile jarFile = new JarFile(file)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    //System.out.println(entry.getName());
                    if (entry.getName().endsWith(prefix + className + ".class")) {
                        try (InputStream is = jarFile.getInputStream(entry)) {
                            ClassReader reader = new ClassReader(is);
                            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                                    //System.out.println(methodName);
                                    if (methodName.equals(name)) {
                                        // https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html
                                        // If 0x80 matches, the last parameter (which will be an array) is actually a varargs argument
                                        boolean isVarargs = (access & 0x80) == 0x80;
                                        System.out.println("Found in: " + entry.getName().replace("/", ".").replace(".class", ""));
                                        Type[] types = Type.getArgumentTypes(descriptor);
                                        boolean found = true;

                                        if (isVarargs) {
                                            outerloop: for (int i = 0; i < Math.min(parameterTypes.length, types.length); i++) {
                                                if(i != types.length-1) {
                                                    if (!parameterIsCompatibleWithType(parameterTypes[i], types[i].getClassName())) {
                                                        found = false;
                                                        break;
                                                    }
                                                }else{
                                                    // Varargs
                                                    String classNameWithoutArrayBrackets = types[i].getClassName().substring(0, types[i].getClassName().indexOf("["));
                                                    if(classNameWithoutArrayBrackets.equals(Object.class.getName())){
                                                        break;
                                                    }
                                                    for (int j = i; j < parameterTypes.length; j++) {
                                                        if(!parameterIsCompatibleWithType(parameterTypes[j], classNameWithoutArrayBrackets)) {
                                                            found = false;
                                                            break outerloop;
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            if (parameterTypes.length == types.length) {

                                                for (int i = 0; i < types.length; i++) {
                                                    //String className = types[i].getClassName();
                                                    if (!parameterIsCompatibleWithType(parameterTypes[i], types[i].getClassName())) {
                                                        found = false;
                                                    }
                                                }
                                            }else{
                                                found = false;
                                            }
                                        }

                                        if (found) {
                                            returnType[0] = Type.getReturnType(descriptor).getClassName();
                                        } else {
                                            returnTypeOfMethodWithSameNumberOfParameters[0] = Type.getReturnType(descriptor).getClassName();
                                        }

                                    }
                                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                                }
                            }, 0);
                            if (returnType[0] != null) {
                                return returnType[0];
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }
        //return returnTypeOfMethodWithSameNumberOfParameters[0];
        return null;
    }

    public static boolean parameterIsCompatibleWithType(String parameter, String type) {
        if(parameter == null){
            return false;
        }
        if(parameter.equals("java.util.function") && type.startsWith("java.util.function")){
            return true;
        }
        if (!type.endsWith(parameter) && (!type.equals(Object.class.getName()))) {
           return false;
        }

        return true;
    }
}
