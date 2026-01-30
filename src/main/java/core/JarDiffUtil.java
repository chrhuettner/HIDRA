package core;


import context.SourceCodeAnalyzer;
import dto.BuildDiffResult;
import dto.ClassDiffResult;
import dto.ClassSearchResult;
import dto.SimilarityResult;
import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.model.*;
import javassist.*;
import llm.WordSimilarityModel;
import solver.nondeterministic.LLMCodeConflictSolver;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class JarDiffUtil {

    private JarArchiveComparator comparator;
    public List<JApiClass> jApiClasses;
    public static final int SIMILARITY_LIMIT = 10;

    public static int methodsPerThread = 500;
    public static int maxThreads = 6;
    private String file1;
    private String file2;
    private static ConcurrentHashMap<Long, ConcurrentHashMap<String, ConcurrentHashMap<String, JarDiffUtil>>> concurrentJarDiffUtils;

    static {
        concurrentJarDiffUtils = new ConcurrentHashMap<>();
    }

    public static JarDiffUtil getInstance(String file1, String file2) {
        return getLazyLoadedInstance(file1, file2);
    }

    public static void removeCachedJarDiffsForThread() {
        long id = Thread.currentThread().threadId();
        System.out.println("THREAD WITH ID " + id + " RELEASES JARDIFF CACHE");
        System.out.println("GLOBAL JARDIFF CACHE SIZE BEFORE: " + concurrentJarDiffUtils.keySet().size());
        concurrentJarDiffUtils.remove(id);
        System.out.println("GLOBAL JARDIFF CACHE SIZE AFTER: " + concurrentJarDiffUtils.keySet().size());
    }

    private static JarDiffUtil getLazyLoadedInstance(String file1, String file2) {
        long id = Thread.currentThread().threadId();

        if (!concurrentJarDiffUtils.containsKey(id)) {
            ConcurrentHashMap<String, ConcurrentHashMap<String, JarDiffUtil>> innerMap = new ConcurrentHashMap<>();
            concurrentJarDiffUtils.put(id, innerMap);
        }

        if (!concurrentJarDiffUtils.get(id).containsKey(file1)) {
            ConcurrentHashMap<String, JarDiffUtil> innerMap = new ConcurrentHashMap<>();
            concurrentJarDiffUtils.get(id).put(file1, innerMap);
        }

        if (!concurrentJarDiffUtils.get(id).get(file1).containsKey(file2)) {
            JarDiffUtil instance = new JarDiffUtil(file1, file2);
            concurrentJarDiffUtils.get(id).get(file1).put(file2, instance);
        }

        return concurrentJarDiffUtils.get(id).get(file1).get(file2);

    }

    private JarDiffUtil(String file1, String file2) {
        this.comparator = createComparator();
        this.file1 = file1;
        this.file2 = file2;
        this.jApiClasses = compareJars(this.comparator, file1, file2);
    }

    public ClassDiffResult getJarDiff(String fullyQualifiedCallerClassName, String methodName, String[] parameterTypeNames, WordSimilarityModel wordSimilarityModel) {
        List<JApiMethod> similarMethods = new ArrayList<>();
        List<JApiMethod> methodsWithSameName = new ArrayList<>();
        List<JApiConstructor> constructors = new ArrayList<>();

        BuildDiffResult changes = buildChangeReport(jApiClasses, fullyQualifiedCallerClassName, methodName, similarMethods, methodsWithSameName, constructors, parameterTypeNames);

        if (changes.classResult().isBlank()) {
            //TODO fullyQualifiedCallerClassName is somehow null here sometimes (c7c9590a206d4fb77dd05b9df391d888e6181667)
            String shortenedClassName = fullyQualifiedCallerClassName.substring(fullyQualifiedCallerClassName.lastIndexOf('.') + 1);
            changes = buildChangeReport(jApiClasses, shortenedClassName, methodName, similarMethods, methodsWithSameName, constructors, parameterTypeNames);
        }

        List<SimilarityResult> similarityResults = new ArrayList<>();
        if (!methodsWithSameName.isEmpty()) {
            if (methodsWithSameName.get(0).getOldMethod().isPresent()) {
                similarityResults = getSimilarityOfMethods(wordSimilarityModel, similarMethods, getFullMethodSignature(methodsWithSameName.get(0).getOldMethod().get().toString(),
                        methodsWithSameName.get(0).getReturnType().getOldReturnType().toString(), true, methodsWithSameName.get(0).getParameters()), SIMILARITY_LIMIT);
            } else if (methodsWithSameName.get(0).getNewMethod().isPresent()) {
                similarityResults = getSimilarityOfMethods(wordSimilarityModel, similarMethods, getFullMethodSignature(methodsWithSameName.get(0).getNewMethod().get().toString(),
                        methodsWithSameName.get(0).getReturnType().getNewReturnType().toString(), true, methodsWithSameName.get(0).getParameters()), SIMILARITY_LIMIT);
            }

        }

        return new ClassDiffResult(changes.classResult(), methodsWithSameName, similarityResults, constructors);
    }

    public String getAlternativeClassImport(String oldClassName) {
        for (JApiClass jApiClass : jApiClasses) {
            if (jApiClass.getFullyQualifiedName().toUpperCase().endsWith(oldClassName.toUpperCase())) {
                if (jApiClass.getChangeStatus() != JApiChangeStatus.REMOVED) {
                    return jApiClass.getFullyQualifiedName();
                }
            }
        }
        return null;
    }

    public List<JApiMethod> getChangedMethods() {
        List<JApiMethod> removedMethods = new ArrayList<>();
        for (JApiClass jApiClass : jApiClasses) {
            if (!jApiClass.getAccessModifier().getNewModifier().orElse(jApiClass.getAccessModifier().getOldModifier().orElse(AccessModifier.PUBLIC)).equals(AccessModifier.PUBLIC) || jApiClass.getChangeStatus() == JApiChangeStatus.REMOVED) {
                continue;
            }
            for (JApiMethod jApiMethod : jApiClass.getMethods()) {
                if (!jApiMethod.getAccessModifier().getNewModifier().orElse(jApiMethod.getAccessModifier().getOldModifier().orElse(AccessModifier.PUBLIC)).equals(AccessModifier.PUBLIC)) {
                    continue;
                }
                if (jApiMethod.getChangeStatus() == JApiChangeStatus.MODIFIED && !jApiMethod.getCompatibilityChanges().isEmpty()) {

                    for (JApiCompatibilityChange jApiCompatibilityChange : jApiMethod.getCompatibilityChanges()) {
                        if (!jApiCompatibilityChange.isBinaryCompatible()) {
                            removedMethods.add(jApiMethod);
                            break;
                        }
                    }

                }
            }
        }
        return removedMethods;
    }

    private static JarArchiveComparator createComparator() {
        Options options = Options.newDefault();
        options.setIgnoreMissingClasses(true);
        JarArchiveComparatorOptions comparatorOptions = JarArchiveComparatorOptions.of(options);
        return new JarArchiveComparator(comparatorOptions);
    }

    private static List<JApiClass> compareJars(JarArchiveComparator comparator, String file1, String file2) {
        JApiCmpArchive left = new JApiCmpArchive(new File(file1), "");
        JApiCmpArchive right = new JApiCmpArchive(new File(file2), "");
        return comparator.compare(left, right);
    }

    private static void addFieldDiffToChangeReport(JApiClass jApiClass, StringBuilder changes) {
        for (JApiField jApiField : jApiClass.getFields()) {
            changes.append(buildFieldChangeReport(jApiField));
            changes.append(System.lineSeparator());
        }
    }

    private static void addMethodDiffToChangeReport(JApiClass jApiClass, String methodName, List<JApiMethod> similarMethods, List<JApiMethod> methodsWithSameName, StringBuilder classChanges, String[] parameterTypeNames) {
        for (JApiMethod jApiMethod : jApiClass.getMethods()) {

            if (jApiMethod.getName().equals(methodName)) {
                methodsWithSameName.add(jApiMethod);

                if (parameterTypeNames != null && !LLMCodeConflictSolver.parametersMatch(jApiMethod.getParameters(), parameterTypeNames)) {
                    similarMethods.add(jApiMethod);
                }

                //methodChanges.append(buildMethodChangeReport(jApiMethod));
                //methodChanges.append(System.lineSeparator()).append(System.lineSeparator());

            } else {
                //if (jApiMethod.getChangeStatus() == JApiChangeStatus.REMOVED) {
                //    continue;
                //}
                similarMethods.add(jApiMethod);
            }


            classChanges.append(buildMethodChangeReport(jApiMethod));
            classChanges.append(System.lineSeparator());
        }
    }

    private BuildDiffResult buildChangeReport(List<JApiClass> jApiClasses, String fullyQualifiedCallerClassName, String methodName, List<JApiMethod> similarMethods, List<JApiMethod> methodsWithSameName, List<JApiConstructor> constructors, String[] parameterTypeNames) {
        StringBuilder classChanges = new StringBuilder();
        //StringBuilder methodChanges = new StringBuilder();
        if (fullyQualifiedCallerClassName == null || fullyQualifiedCallerClassName.isEmpty()) {
            return new BuildDiffResult(classChanges.toString());
        }

        for (JApiClass jApiClass : jApiClasses) {
            /*if (jApiClass.getChangeStatus() == JApiChangeStatus.UNCHANGED) {
                continue;
            }*/
            //System.out.println(jApiClass.getFullyQualifiedName());
            if (!jApiClass.getFullyQualifiedName().toUpperCase().endsWith(fullyQualifiedCallerClassName.toUpperCase())) {
                continue;
            }

            classChanges.append(buildClassChangeHeader(jApiClass));
            classChanges.append(System.lineSeparator());

            if (!jApiClass.getFields().isEmpty()) {
                classChanges.append("Fields: ").append(System.lineSeparator());

                addFieldDiffToChangeReport(jApiClass, classChanges);
            }

            if (!jApiClass.getConstructors().isEmpty()) {
                classChanges.append(System.lineSeparator());
                classChanges.append("Constructors: ").append(System.lineSeparator());
            }

            //if (methodName != null && methodName.equals(fullyQualifiedCallerClassName)) {
            for (JApiConstructor jApiConstructor : jApiClass.getConstructors()) {
                constructors.add(jApiConstructor);

                classChanges.append(buildConstructorChangeReport(jApiConstructor));
                classChanges.append(System.lineSeparator());
            }
            //}

            if (!jApiClass.getMethods().isEmpty()) {
                classChanges.append(System.lineSeparator());
                classChanges.append("Class methods: ").append(System.lineSeparator());
                addMethodDiffToChangeReport(jApiClass, methodName, similarMethods, methodsWithSameName, classChanges, parameterTypeNames);
            }
            StringBuilder removedInterfaces = new StringBuilder();
            removedInterfaces.append("Removed interfaces: ").append(System.lineSeparator());

            boolean includeRemovedInterfaces = false;

            for (JApiImplementedInterface jApiImplementedInterface : jApiClass.getInterfaces()) {
                if (jApiImplementedInterface.getCorrespondingJApiClass().isPresent()) {
                    addMethodDiffToChangeReport(jApiImplementedInterface.getCorrespondingJApiClass().get(), methodName, similarMethods, methodsWithSameName, classChanges, parameterTypeNames);
                } else {
                    removedInterfaces.append(jApiImplementedInterface.getFullyQualifiedName()).append(System.lineSeparator());
                    for (CtMethod method : jApiImplementedInterface.getCtClass().getMethods()) {

                        if (method.getName().equals(methodName)) {
                            JApiMethod dummyMethod = new JApiMethod(null, method.getName(), JApiChangeStatus.REMOVED, Optional.of(method), Optional.empty(), this.comparator);
                            methodsWithSameName.add(dummyMethod);
                        }

                        String methodSignature = method.toString();
                        methodSignature = methodSignature.substring(methodSignature.indexOf("[") + 1, methodSignature.lastIndexOf("]"));
                        if (!methodSignature.contains(" native ")) {
                            removedInterfaces.append("- ").append(methodSignature).append(System.lineSeparator());
                            includeRemovedInterfaces = true;
                        }
                    }
                }
            }

            if (includeRemovedInterfaces) {
                classChanges.append(removedInterfaces);
            }

            classChanges.append(System.lineSeparator()).append(System.lineSeparator());

            //classChanges.append("End of changed class methods.").append(System.lineSeparator());
        }


        return new BuildDiffResult(classChanges.toString());
    }

    private static String buildClassChangeHeader(JApiClass jApiClass) {
        StringBuilder header = new StringBuilder();
        List<JApiCompatibilityChange> classCompatibilityChanges = jApiClass.getCompatibilityChanges();

        String classCompatibilityChange = joinCompatibilityChanges(classCompatibilityChanges);

        header.append("Changed class: ")
                .append(jApiClass.getAccessModifier().getNewModifier().orElse(jApiClass.getAccessModifier().getOldModifier().orElse(null)))
                .append(" ")
                .append(jApiClass.getFullyQualifiedName())
                .append(", Status: ")
                .append(jApiClass.getChangeStatus());


        if (!classCompatibilityChange.isEmpty()) {
            header.append(", Compatibility change: ").append(classCompatibilityChange);
        }

        return header.toString();
    }

    public static String buildFieldChangeReport(JApiField jApiField) {
        if (jApiField == null) {
            return "";
        }
        StringBuilder report = new StringBuilder();
        List<JApiCompatibilityChange> compatibilityChanges = jApiField.getCompatibilityChanges();

        report.append("- ");

        //String parameters = buildParameterString(jApiMethod.getParameters());
        CtField field = jApiField.getNewFieldOptional().orElse(jApiField.getOldFieldOptional().orElse(null));


        try {
            if (field != null) {
                report.append(Modifier.toString(field.getModifiers())).append(" ").append(field.getType().getName()).append(" ").append(field.getName());
            }
        } catch (NotFoundException e) {
            System.err.println("Could not find field: " + jApiField.getName());
        }

        String compatibilityChange = joinCompatibilityChanges(compatibilityChanges);
        if (!compatibilityChange.isEmpty()) {
            report.append(", Compatibility change: ").append(compatibilityChange);
        }

        return report.toString();
    }

    public static String buildMethodChangeReport(JApiMethod jApiMethod) {
        if (jApiMethod == null) {
            return "";
        }
        StringBuilder report = new StringBuilder();
        List<JApiCompatibilityChange> compatibilityChanges = jApiMethod.getCompatibilityChanges();

        report.append("- ");

        //String parameters = buildParameterString(jApiMethod.getParameters());
        CtMethod method = jApiMethod.getNewMethod().orElse(jApiMethod.getOldMethod().orElse(null));
        String returnType = jApiMethod.getReturnType().getNewReturnType().toString();


        if (returnType.equals("n.a.")) {
            returnType = jApiMethod.getReturnType().getOldReturnType().toString();
        }

        String generic = method.getGenericSignature();

        if (generic != null) {
            // Find return type
            String returnTypePart = generic.substring(generic.indexOf(')') + 1); // "Ljava/util/concurrent/CompletableFuture<Ljava/lang/Void;>;"

            if (returnTypePart.contains("<") && returnTypePart.contains(">")) {
                String genericPart = returnTypePart.substring(returnTypePart.indexOf('<') + 1, returnTypePart.indexOf('>')); // "Ljava/lang/Void;"
                String genericClass;
                if (genericPart.equals("*")) {
                    genericClass = "*";
                } else {
                    genericClass = genericPart.substring(1, genericPart.length() - 1).replace('/', '.');
                }
                returnType += "<" + genericClass + ">";
            }
        }

        String parameters = getFullMethodSignature(method.toString(), returnType, true, jApiMethod.getParameters());
        if (!parameters.isEmpty()) {
            report.append(parameters).append(", ");
        }

        report.append(buildReturnTypeChangeString(jApiMethod.getReturnType()));

        String compatibilityChange = joinCompatibilityChanges(compatibilityChanges);
        if (!compatibilityChange.isEmpty()) {
            report.append(", Compatibility change: ").append(compatibilityChange);
        }

        return report.toString();
    }

    public static String buildConstructorChangeReport(JApiConstructor jApiConstructor) {
        if (jApiConstructor == null) {
            return "";
        }
        StringBuilder report = new StringBuilder();
        List<JApiCompatibilityChange> compatibilityChanges = jApiConstructor.getCompatibilityChanges();

        report.append("- ");

        //String parameters = buildParameterString(jApiMethod.getParameters());

        CtConstructor constructor = jApiConstructor.getNewConstructor().orElse(jApiConstructor.getOldConstructor().orElse(null));
        String returnType = constructor.getName();

        String parameters = getFullMethodSignature(constructor.toString(), returnType, false, jApiConstructor.getParameters());
        if (!parameters.isEmpty()) {
            report.append(parameters);
        }

        String compatibilityChange = joinCompatibilityChanges(compatibilityChanges);
        if (!compatibilityChange.isEmpty()) {
            report.append(", Compatibility change: ").append(compatibilityChange);
        }

        return report.toString();
    }

    private static String joinCompatibilityChanges(List<JApiCompatibilityChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < changes.size(); i++) {
            sb.append(changes.get(i).getType());
            if (i != changes.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }


    private static String buildParameterString(List<JApiParameter> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("Parameters: ");
        for (int i = 0; i < parameters.size(); i++) {
            JApiParameter param = parameters.get(i);
            sb.append(param.getType())
                    .append(" (")
                    .append(param.getChangeStatus())
                    .append(")");

            if (i != parameters.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private static String buildReturnTypeChangeString(JApiReturnType returnType) {
        String oldType = returnType.getOldReturnType();
        String newType = returnType.getNewReturnType();

        if (!oldType.equals(newType)) {
            StringBuilder sb = new StringBuilder();
            if (!oldType.equals("n.a.")) {
                sb.append("Old return type: ").append(oldType);
            }
            if (!newType.equals("n.a.")) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("New return type: ").append(newType);
            }
            return sb.toString();
        } else {
            return "Return type: " + newType;
        }
    }

    private static List<SimilarityResult> getSimilarityOfMethods(WordSimilarityModel wordSimilarityModel, List<JApiMethod> methods, String oldMethod, int limit) {
        double[] baseVec = wordSimilarityModel.getEmbedding(oldMethod);
        List<SimilarityResult> similarityResults = new ArrayList<>();

        for (JApiMethod method : methods) {
            if (method.getChangeStatus() == JApiChangeStatus.REMOVED) {
                continue;
            }
            double[] compareVec = wordSimilarityModel.getEmbedding(getFullMethodSignature(method.getNewMethod().
                    get().toString(), method.getReturnType().getNewReturnType().toString(), true, method.getParameters()));
            double similarity = WordSimilarityModel.cosineSimilarity(baseVec, compareVec);
            similarityResults.add(new SimilarityResult(method, similarity));
        }

        similarityResults.sort((t1, t2) -> Double.compare(t2.similarity(), t1.similarity()));

        while (similarityResults.size() > limit) {
            similarityResults.removeLast();
        }

        return similarityResults;
    }

    private static String getMethodSignatureFromUnChangedMethod(JApiMethod method) {
        return getFullMethodSignature(method.getOldMethod().get().toString(), method.getReturnType().getOldReturnType().toString(), true, method.getParameters());
    }

    public ConcurrentHashMap<String, List<double[]>> getMethodEmbeddingsOfDependency(WordSimilarityModel wordSimilarityModel) {
        ConcurrentHashMap<String, List<double[]>> embeddings = new ConcurrentHashMap<>();

        String strippedFileName = this.file1;
        if (strippedFileName.contains("/")) {
            strippedFileName = strippedFileName.substring(strippedFileName.lastIndexOf("/") + 1);
        }
        if (strippedFileName.contains("\\")) {
            strippedFileName = strippedFileName.substring(strippedFileName.lastIndexOf("\\") + 1);
        }

        if (strippedFileName.endsWith(".jar")) {
            strippedFileName = strippedFileName.substring(0, strippedFileName.lastIndexOf("."));
        }

        Path cachePath = Path.of("embeddings/" + strippedFileName);
        if (Files.exists(cachePath)) {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(cachePath.toFile()))) {
                System.out.println("Loading " + strippedFileName + " from cache");
                return (ConcurrentHashMap) in.readObject();
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        List<JApiMethod> methods = new ArrayList<>();
        for (JApiClass jApiClass : jApiClasses) {
            for (JApiMethod method : jApiClass.getMethods()) {
                methods.add(method);
            }
        }

        if (methods.isEmpty()) {
            return embeddings;
        }
        System.out.println("Creating embeddings folder for caching");
        new File("embeddings").mkdirs();
        System.out.println("Generating embeddings of " + methods.size() + " methods");
        int amountOfThreads = Math.min(maxThreads, (int) Math.ceil((double) methods.size() / methodsPerThread));
        System.out.println("Splitting work into " + amountOfThreads + " thread(s)");

        List<Thread> threads = new ArrayList<>();
        List<AtomicInteger> finishedMethods = Collections.synchronizedList(new ArrayList<>());
        int workSplit = methods.size() / amountOfThreads;
        for (int i = 0; i < amountOfThreads; i++) {
            int finalI = i;
            Thread virtualThread = Thread.ofVirtual().start(() -> {
                AtomicInteger finishedMethodCounter = new AtomicInteger();
                finishedMethods.add(finishedMethodCounter);
                for (int j = workSplit * finalI; j < Math.min(workSplit * (finalI + 1), methods.size()); j++) {
                    JApiMethod method = methods.get(j);

                    int finalJ = j;
                    embeddings.compute(
                            method.getjApiClass().getFullyQualifiedName(),
                            (key, list) -> {
                                if (list == null) {
                                    list = new ArrayList<>();
                                }
                                double[] methodEmbedding = wordSimilarityModel.getEmbedding(getMethodSignatureFromUnChangedMethod(method));

                                list.add(methodEmbedding);
                                return list;
                            }
                    );

                    finishedMethodCounter.incrementAndGet();
                }
            });
            threads.add(virtualThread);
        }
        AtomicBoolean finished = new AtomicBoolean();

        Thread.ofVirtual().start(() -> {
            while (!finished.get()) {
                int sum = 0;
                StringBuilder line = new StringBuilder("Finished methods per worker: [");

                for (int i = 0; i < finishedMethods.size(); i++) {
                    int count = finishedMethods.get(i).get();
                    sum += count;

                    line.append(count);
                    if (i < finishedMethods.size() - 1) {
                        line.append(", ");
                    }
                }

                line.append("] | Total: ").append(sum).append("/").append(methods.size());

                System.out.println(line);

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        finished.set(true);

        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(cachePath.toFile()))) {
            out.writeObject(embeddings);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return embeddings;
    }

    public static Map<String, Double> getClassMethodsSimilarity(ConcurrentHashMap<String, List<double[]>> embeddings) {
        HashMap<String, Double> classMethodsSimilarity = new HashMap<>();
        for (Map.Entry<String, List<double[]>> entry : embeddings.entrySet()) {
            List<double[]> embeddingsOfClass = entry.getValue();
            double sumOfSimilarities = 0;
            double comparedMethods = 0;
            for (int i = 0; i < embeddingsOfClass.size(); i++) {
                double[] baseVec = embeddingsOfClass.get(i);
                for (int j = 0; j < embeddingsOfClass.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    double[] compareVec = embeddingsOfClass.get(j);
                    double similarity = WordSimilarityModel.cosineSimilarity(baseVec, compareVec);
                    sumOfSimilarities += similarity;
                    comparedMethods++;
                }
            }
            classMethodsSimilarity.put(entry.getKey(), sumOfSimilarities / comparedMethods);
        }

        return classMethodsSimilarity;
    }


    public static String getFullMethodSignature(String methodString, String returnType, boolean addReturnType, List<JApiParameter> parameters) {
        String result = methodString;

        String resultPrefix = result.substring(result.indexOf("[") + 1, result.indexOf("("));

        String[] keywords = {
                "public", "protected", "private",
                "static", "final", "abstract",
                "synchronized", "native", "strictfp",
                "default", "volatile", "transient"
        };
        if (addReturnType) {
            int lastKeywordIndex = -1;

            for (String kw : keywords) {
                int idx = resultPrefix.lastIndexOf(kw);
                if (idx > lastKeywordIndex) {
                    lastKeywordIndex = idx;
                }
            }


            if (lastKeywordIndex != -1) {
                int insertPos = resultPrefix.indexOf(" ", lastKeywordIndex);
                if (insertPos == -1) insertPos = resultPrefix.length();
                resultPrefix = resultPrefix.substring(0, insertPos) + " " + returnType + resultPrefix.substring(insertPos);
            } else {
                int firstSpace = resultPrefix.indexOf(" ");
                if (firstSpace == -1) firstSpace = resultPrefix.length();
                resultPrefix = resultPrefix.substring(0, firstSpace) + " " + returnType + resultPrefix.substring(firstSpace);
            }
        }


        StringBuilder parameterResult = new StringBuilder("(");

        for (int i = 0; i < parameters.size(); i++) {
            String parameterRepresentation = parameters.get(i).getType();
            if (parameters.get(i).getChangeStatus() != JApiChangeStatus.UNCHANGED) {
                parameterRepresentation += " (" + parameters.get(i).getChangeStatus() + ")";
            }
            if (i != parameters.size() - 1) {
                parameterRepresentation += ", ";
            }
            parameterResult.append(parameterRepresentation);
        }

        parameterResult.append(")");


        return resultPrefix + parameterResult;
    }

    private String getMethodReturnTypeFromClass(JApiClass jApiClass, String methodName) {
        for (JApiMethod jApiMethod : jApiClass.getMethods()) {
            if (jApiMethod.getName().equals(methodName)) {
                return jApiMethod.getReturnType().getOldReturnType();
            }
        }
        return null;
    }

    public JApiMethod getMethodOfClass(JApiClass jApiClass, String methodName, String[] parameters) {
        List<JApiMethod> methods = jApiClass.getMethods();
        List<JApiMethod> methodsWithSameNameAndParameterCount = new ArrayList<>();
        outerloop:
        for (JApiMethod jApiMethod : jApiClass.getMethods()) {
            if (jApiMethod.getName().equals(methodName)) {
                if (parameters == null) {
                    methods.add(jApiMethod);
                } else {
                    if (parameters.length != jApiMethod.getParameters().size()) {
                        continue;
                    }
                    CtMethod oldMethod = jApiMethod.getOldMethod().orElse(jApiMethod.getNewMethod().orElse(null));
                    if(oldMethod == null) {
                        continue;
                    }
                    for (int i = 0; i < parameters.length; i++) {
                        try {
                            if (!SourceCodeAnalyzer.parameterIsCompatibleWithType(parameters[i], oldMethod.getParameterTypes()[i].getName())) {
                                methodsWithSameNameAndParameterCount.add(jApiMethod);
                                continue outerloop;
                            }
                        } catch (NotFoundException e) {
                            continue outerloop;
                        }
                    }
                    return jApiMethod;
                }
            }
        }

        for (JApiImplementedInterface jApiImplementedInterface : jApiClass.getInterfaces()) {
            if (jApiImplementedInterface.getCorrespondingJApiClass().isPresent()) {
                JApiMethod recursiveResult = getMethodOfClass(jApiImplementedInterface.getCorrespondingJApiClass().get(), methodName, parameters);
                if (recursiveResult != null) {
                    return recursiveResult;
                }
            }
        }

        if (!methodsWithSameNameAndParameterCount.isEmpty()) {
            if (methodsWithSameNameAndParameterCount.size() == 1) {
                return methodsWithSameNameAndParameterCount.get(0);
            }
            for (JApiMethod jApiMethod : methodsWithSameNameAndParameterCount) {
                if (jApiMethod.getChangeStatus() != JApiChangeStatus.REMOVED) {
                    return jApiMethod;
                }
            }
        }
        return null;
    }

    public ClassSearchResult getClassesByName(String className) {
        List<JApiClass> exactClassMatches = new ArrayList<>();
        List<JApiClass> suffixClassMatches = new ArrayList<>();
        for (JApiClass jApiClass : jApiClasses) {
            if (jApiClass.getFullyQualifiedName().equals(className)) {
                exactClassMatches.add(jApiClass);
            } else if (jApiClass.getFullyQualifiedName().toUpperCase().endsWith(className.toUpperCase())) {
                suffixClassMatches.add(jApiClass);
            }
        }
        return new ClassSearchResult(exactClassMatches, suffixClassMatches);
    }

    public JApiClass getClassByName(String className) {
        for (JApiClass jApiClass : jApiClasses) {
            if (jApiClass.getFullyQualifiedName().equals(className)) {
                return jApiClass;
            }
        }
        return null;
    }

    public String getMethodReturnType(String className, String methodName) {
        String returnName = null;
        outerloop:
        for (JApiClass jApiClass : jApiClasses) {
            if (!jApiClass.getFullyQualifiedName().toUpperCase().endsWith(className.toUpperCase())) {
                continue;
            }

            returnName = getMethodReturnTypeFromClass(jApiClass, methodName);

            if (returnName != null) {
                break;
            }
            //Maybe its an interface that extends an interface that has the method
            for (JApiImplementedInterface jApiImplementedInterface : jApiClass.getInterfaces()) {
                if (jApiImplementedInterface.getCorrespondingJApiClass().isPresent()) {
                    returnName = getMethodReturnTypeFromClass(jApiImplementedInterface.getCorrespondingJApiClass().get(), methodName);
                    if (returnName != null) {
                        break outerloop;
                    }
                }
            }
        }


       /* for (JApiClass jApiClass : jApiClasses) {
            for (JApiMethod jApiMethod : jApiClass.getMethods()) {

                if (jApiMethod.getName().equals(methodName)) {
                    return jApiMethod.getReturnType().getOldReturnType();
                }
            }
        }*/
        return returnName;
    }
}

