package solver.nondeterministic;

import context.LogParser;
import context.SourceCodeAnalyzer;
import core.*;
import context.Context;
import context.ContextUtil;
import docker.ContainerUtil;
import dto.*;
import japicmp.model.JApiConstructor;
import japicmp.model.JApiMethod;
import japicmp.model.JApiParameter;
import llm.LLMProvider;
import llm.AIProviderException;
import llm.WordSimilarityModel;
import solver.ContextAwareSolver;
import type.ConflictType;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static core.BumpRunner.usePromptCaching;

public class LLMCodeConflictSolver extends ContextAwareSolver {

    static String systemContext = """
            You are a software engineer assistant specialized in resolving dependency conflicts by modifying source code.
            Given code diffs between different versions of a shared dependency and client library code that breaks because of API changes, you suggest minimal, safe code adaptations so the client library works correctly with the target dependency version.
            Focus on clear, maintainable code changes and explain your reasoning if needed.
            """;

    static String promptTemplatePrefix = """
            You are a software migration assistant.
            
            I am upgrading my project from %s %s to version %s of a dependency that includes breaking API changes.
            
            You are given the following input:
            """;
    static String promptTemplateMethodDiff = """
            **Method-level diff** showing changes to the method being used in my code:
            ```diff
            %s
            ```
            """;
    static String promptTemplateFullDiff = """
            **Full diff of the dependency** between versions %s and %s (for additional context):
            ```diff
            %s
            ```
            """;
    static String promptTemplateErroneousClass = """
            
            Class from my project that is broken after the upgrade to version %s, provided to you as a list seperated by commas:
            ```
            %s
            ```
            """;

    static String promptTemplateErroneousScope = """
            
            Scope from my project that is broken after the upgrade:
            ```
            %s
            ```
            """;
    static String promptTemplateCodeLine = """ 
            **Line of code from my project** that is broken after the upgrade to version %s:
            ```java
            %s
            ```
            """;
    static String promptTemplateSimilarity = """  
            
            **Method-similarity** showing similar methods that could be used instead of the broken one:
            ```similarity
            %s
            ```
            """;
    static String promptTemplateError = """
            
            **Error** showing the error:
            ```
            %s
            ```
            """;


    static String promptTemplateSuffix = """    
             ----
            
             Your task:
             - Read both diffs and the broken code snippet.
             - Identify what changed in the method being used.
             - Consider the method similarity when you are not sure which method to choose.
             - Focus your reasoning and output **only on the provided broken line**, not on the entire method or class scope.
             - Output the following in the exact format below.
            
             ----
            
             **RESPONSE FORMAT (STRICT)**
            
             1. A **very brief** explanation of why the code is failing.
                - Maximum 2 sentences.
                - No introductions like "Sure" or "Here's the issue".
            
             2. Then output **only the changed or inserted lines**, using this exact format (do not deviate):
            
             ```
             ---BEGIN UPDATED java CODE---
             // Insert your updated java code here
             ---END UPDATED java CODE---
             ```
            
             ----
            
             **Rules**:
            - Do NOT include full classes or unrelated methods.
            - Do NOT output anything before or after the required format.
            - Do NOT explain anything outside the two-sentence explanation above.
            - Do NOT include headings, intros, or closing remarks.
            - Do NOT add a semicolon to your code if the original code did not end with it.
            - Do NOT call private or protected methods unless they are accessible from the class.
            - Only start your updated java code with slashes if you want it to be commented out code.
            - Preserve all trailing symbols (such as braces, semicolons, parentheses, commas) exactly as in the original code to ensure it compiles.
            - When the error message occurs on a return statement, inspect the expected type in the error message and make the returned expression match that type.
            - Restrict changes strictly to the given broken line. Do not rewrite the surrounding method or signature unless the error explicitly requires it.
            - Always ensure that the types you construct or return match the expected types indicated by the method signature or compiler error.
            - When a type mismatch occurs, fix the type by using the correct class or constructor rather than forcing a conversion.
            - Prefer consistency with the dependency’s updated API (as shown in the diff) over preserving old code patterns.
            - Avoid guessing types based on naming; instead, infer them from the diff, return types, and error messages.
            - Always use fully qualified class names for any classes not imported in the original file or when introducing new classes, to avoid ambiguity.
            - Only modify the single broken line. Do NOT include, rewrite, or re-emit any surrounding code. The output is automatically parsed as a patch for that one line; if you include any surrounding scope (methods, classes, braces, imports, or extra lines), the result will be considered invalid and will break the automated pipeline.
            
             Your response will be **automatically parsed**, so it must match the format **exactly**.
            """;
    private LLMProvider LLMProvider;

    public LLMCodeConflictSolver(Context context, LLMProvider LLMProvider) {
        super(context);
        this.LLMProvider = LLMProvider;
    }

    public LLMProvider getProvider() {
        return LLMProvider;
    }

    public String buildPrompt(BrokenCode brokenCode, ErrorLocation errorLocation, int iteration) {

        List<String> erroneousClassLinesList = readBrokenClass(context.getStrippedClassName(), context.getTargetDirectoryClasses(), context.getStrippedFileName(), iteration);
        String erroneousScope = readBrokenEnclosingScope(context.getStrippedClassName(), context.getTargetDirectoryClasses(), context.getStrippedFileName(), context.getCompileError().line, iteration);

        String packageName = "";

        for (String line : erroneousClassLinesList) {
            if (line.trim().startsWith("package")) {
                packageName = line.substring(line.indexOf("package") + "package".length() + 1, line.indexOf(";"));
                break;
            }
        }

        String fullClassName = packageName + "." + context.getStrippedClassName();
        String errorPrompt = fullClassName + System.lineSeparator() + "line: " + context.getCompileError().line + ", column: " + context.getCompileError().column + System.lineSeparator() + context.getCompileError().message;

        for (String detail : context.getCompileError().details.keySet()) {
            errorPrompt = errorPrompt + System.lineSeparator() + detail + " " + context.getCompileError().details.get(detail);
        }

        return assemblePrompt(context.getDependencyArtifactId(), context.getPreviousVersion(), context.getNewVersion(), context.getTargetPathOld().toString(), context.getTargetPathNew().toString(),
                errorLocation.className(), errorLocation.methodName(), brokenCode.code(), errorLocation.targetMethodParameterClassNames(), errorPrompt, erroneousClassLinesList.toString(), erroneousScope, context.getWordSimilarityModel(), context.getConfig().getDisabledPromptComponents());
    }

    public String assemblePrompt(String libraryName, String oldVersion, String newVersion, String pathToOldLibraryJar, String pathToNewLibraryJar, String brokenClassName,
                                 String brokenMethodName, String brokenCode, String[] parameterTypeNames, String error, String erroneousClass, String erroneousScope, WordSimilarityModel wordSimilarityModel, Set<String> disabledPromptComponents) {
        JarDiffUtil jarDiffUtil;
        if (brokenClassName != null && brokenClassName.startsWith("java.")) {
            jarDiffUtil = JarDiffUtil.getInstance("Java_Src/rt.jar", "Java_Src/rt.jar");
        } else {
            jarDiffUtil = JarDiffUtil.getInstance(pathToOldLibraryJar, pathToNewLibraryJar);
        }


        String methodChange = "";
        ClassDiffResult classDiffResult = null;
        StringBuilder methodSimilarityString = new StringBuilder();
        if (brokenClassName != null) {
            classDiffResult = jarDiffUtil.getJarDiff(brokenClassName, brokenMethodName, parameterTypeNames, wordSimilarityModel);

            if (classDiffResult.classDiff().isEmpty()) {
                SourceCodeAnalyzer sourceCodeAnalyzer = SourceCodeAnalyzer.getInstance(context.getOutputDirSrcFiles().toPath().resolve(Path.of(context.getDependencyArtifactId() + "_" + context.getStrippedFileName())).toString());
                FileSearchResult searchResult = sourceCodeAnalyzer.getDependencyFileContainingClass(brokenClassName);


                if (searchResult != null) {
                    System.out.println("Dependency RESULT: ");
                    System.out.println(searchResult.file().getAbsolutePath());
                    System.out.println(searchResult.entry().getName());

                    JarDiffUtil dependencyDiffUtil = JarDiffUtil.getInstance(searchResult.file().getAbsolutePath(), searchResult.file().getAbsolutePath());
                    classDiffResult = dependencyDiffUtil.getJarDiff(brokenClassName, brokenMethodName, parameterTypeNames, wordSimilarityModel);
                }
            }


            for (SimilarityResult result : classDiffResult.similarMethods()) {
                String formattedString = String.format("Similarity between '%s' and '%s': %.4f%n", brokenMethodName, JarDiffUtil.getFullMethodSignature(result.method().getNewMethod().get().toString(),
                        result.method().getReturnType().getNewReturnType(), true, result.method().getParameters()), result.similarity());
                methodSimilarityString.append(formattedString);
            }

            JApiConstructor constructor;
            JApiMethod conflictingMethod;


            conflictingMethod = inferTargetMethod(classDiffResult.methodsWithSameName(), parameterTypeNames);
            if (conflictingMethod != null) {
                System.out.println(conflictingMethod);
                methodChange = JarDiffUtil.buildMethodChangeReport(conflictingMethod);
            } else {
                constructor = inferTargetConstructor(classDiffResult.constructors(), parameterTypeNames);
                System.out.println(constructor);
                methodChange = JarDiffUtil.buildConstructorChangeReport(constructor);
            }
        }

        //List<ConflictType> conflictTypes = ConflictType.getConflictTypesFromMethod(conflictingMethod);

        /*for (ConflictType conflictType : conflictTypes) {
            System.out.println(conflictType.name());
        }*/

        StringBuilder assembledPrompt = new StringBuilder();

        assembledPrompt.append(String.format(promptTemplatePrefix, libraryName, oldVersion, newVersion));

        // 4 ... 9
        boolean disableMethodChanges = (1 + context.getRetry()) % 5 == 0;
        // 3 ... 8
        boolean disableClassDiff = (2 + context.getRetry()) % 5 == 0;
        // 2 ... 7
        boolean disableScope = (3 + context.getRetry()) % 5 == 0;
        // 1 ... 6
        boolean disableMethodSimilarity = (4 + context.getRetry()) % 5 == 0;

        if (!methodChange.isEmpty() && !disabledPromptComponents.contains("methodchange") && !disableMethodChanges) {
            assembledPrompt.append(String.format(promptTemplateMethodDiff, methodChange));
        }

        if (classDiffResult != null && !classDiffResult.classDiff().isEmpty() && !disabledPromptComponents.contains("classdiff") && !disableClassDiff) {
            assembledPrompt.append(String.format(promptTemplateFullDiff, newVersion, oldVersion, classDiffResult.classDiff()));
        }

        if (!erroneousClass.isEmpty()) {
            //assembledPrompt.append(String.format(promptTemplateErroneousClass,  newVersion, erroneousClass));
        }

        if (!erroneousScope.isEmpty() && !disabledPromptComponents.contains("scope") && !disableScope) {
            assembledPrompt.append(String.format(promptTemplateErroneousScope, erroneousScope));
        }

        if (!brokenCode.isEmpty() && !disabledPromptComponents.contains("brokencode")) {
            assembledPrompt.append(String.format(promptTemplateCodeLine, newVersion, brokenCode));
        }

        if (!methodSimilarityString.isEmpty() && !disabledPromptComponents.contains("methodsimilarity") && !disableMethodSimilarity) {
            assembledPrompt.append(String.format(promptTemplateSimilarity, methodSimilarityString));
        }

        if (!error.isEmpty() && !disabledPromptComponents.contains("error")) {
            assembledPrompt.append(String.format(promptTemplateError, error));
        }

        assembledPrompt.append(promptTemplateSuffix);


        return assembledPrompt.toString();
    }

    public static boolean parametersMatch(List<JApiParameter> parameters1, String[] parameters2) {
        if (parameters2 == null || parameters1.size() != parameters2.length) {
            return false;
        }

        for (int i = 0; i < parameters1.size(); i++) {
            JApiParameter parameter = parameters1.get(i);
            if (parameters2[i] == null) {
                return false;
            }
            if (!ContextUtil.primitiveClassNameToWrapperName(parameter.getType()).endsWith(ContextUtil.primitiveClassNameToWrapperName(parameters2[i])) && !parameters2[i].equals("java.lang.Object")) {
                return false;
            }
        }
        return true;
    }

    public JApiConstructor inferTargetConstructor(List<JApiConstructor> constructors, String[] parameterTypeNames) {
        if (constructors == null || constructors.isEmpty()) {
            return null;
        }
        ArrayList<JApiConstructor> candidates = new ArrayList<>(constructors.size());
        candidates.addAll(constructors);


        for (int i = candidates.size() - 1; i >= 0; i--) {
            JApiConstructor constructor = candidates.get(i);
            if (!parametersMatch(constructor.getParameters(), parameterTypeNames)) {
                candidates.remove(i);
            }
        }

        if (candidates.isEmpty()) {
            System.err.println("No constructors with the same signature found");
            return null;
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }


        return candidates.get(0);
    }

    public JApiMethod inferTargetMethod(List<JApiMethod> methodsWithSameName, String[] parameterTypeNames) {
        if (methodsWithSameName == null || methodsWithSameName.isEmpty()) {
            return null;
        }
        ArrayList<JApiMethod> candidates = new ArrayList<>(methodsWithSameName.size());
        candidates.addAll(methodsWithSameName);


        for (int i = candidates.size() - 1; i >= 0; i--) {
            JApiMethod method = candidates.get(i);
            if (!parametersMatch(method.getParameters(), parameterTypeNames)) {
                candidates.remove(i);
            }
        }

        if (candidates.isEmpty()) {
            System.err.println("No methods with the same signature found");


            return null;
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }


        return candidates.get(0);
    }

    public ConflictResolutionResult sendAndPrintCode(String prompt, double temperature, double top_k) {
        System.out.println(LLMProvider.getModel());
        ConflictResolutionResult result = LLMProvider.sendPromptAndReceiveResponse(prompt, systemContext, temperature, top_k);
        System.out.println(result);
        return result;
    }

    public List<String> readBrokenClass(String className, String directory, String fileName, int iteration) {
        try {
            return Files.readAllLines(ContainerUtil.getPathWithRespectToIteration(directory, fileName, className, iteration, true));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public String readBrokenEnclosingScope(String className, String directory, String fileName, int lineNumber, int iteration) {

        // Unfeasible due to too many edge cases, instead use simple brace matching
        //Pattern methodDeclarationPattern = Pattern.compile("(public|protected|private|static|final|abstract|\\s)+\\s+[\\w\\<\\>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{");
        try {
            List<String> lines = Files.readAllLines(ContainerUtil.getPathWithRespectToIteration(directory, fileName, className, iteration, true));

            int start = -1;
            int openBraces = 0;
            int closedBraces = 0;
            for (int i = lineNumber - 1; i >= 0; i--) {
                String line = lines.get(i);
                for (int j = 0; j < line.length(); j++) {
                    char c = line.charAt(j);
                    if (c == '{') {
                        openBraces++;
                    } else if (c == '}') {
                        closedBraces++;
                    }
                }

                if (openBraces == closedBraces + 1) {
                    start = i;
                    break;
                }
                /*Matcher declarationMatcher = methodDeclarationPattern.matcher(lines.get(i));

                if (declarationMatcher.find()) {
                    start = i;
                    break;
                }*/
            }

            if (start == -1) {
                System.err.println("No enclosing scope found");
                return "";
            }

            int openCurlyBraces = 0;
            int closedCurlyBraces = 0;

            int end = 0;

            outerloop:
            for (int i = start; i < lines.size(); i++) {
                for (int j = 0; j < lines.get(i).length(); j++) {
                    char c = lines.get(i).charAt(j);
                    if (c == '{') {
                        openCurlyBraces++;
                    } else if (c == '}') {
                        closedCurlyBraces++;
                    }

                    if (openCurlyBraces == closedCurlyBraces && openCurlyBraces != 0 && i >= lineNumber - 1) {
                        end = i;
                        break outerloop;
                    }
                }
            }

            String scope = "";

            for (int i = start; i <= end; i++) {
                scope = scope + lines.get(i) + System.lineSeparator();
            }

            return scope;


        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public boolean errorIsTargetedBySolver(LogParser.CompileError compileError, BrokenCode brokenCode, ErrorLocation errorLocation, List<ConflictType> conflictTypes) {
        return true;
    }

    private String cleanClassName(String className) {
        if (className == null) {
            return "unknownClass";
        }
        return className.replaceAll(" ", "").replaceAll("\\?", "generic");
    }

    @Override
    public ProposedChange solveConflict(LogParser.CompileError compileError, BrokenCode brokenCode, ErrorLocation errorLocation) {
        ConflictResolutionResult result = null;

        String cleanedProviderModelName = context.getActiveProvider().getModel().replaceAll("\\W+", "");
        String cleanedClassName = cleanClassName(errorLocation.className());
        Path promptFilePath = Path.of(context.getTargetDirectoryPrompts() + "/iteration_" + context.getIteration() + "/" + context.getStrippedFileName() + "_" + context.getCompileError().line + "_" + cleanedClassName + "_" + cleanedProviderModelName + ".txt");
        Path llmResponseFilePath = Path.of(context.getTargetDirectoryLLMResponses() + "/iteration_" + context.getIteration() + "/" + context.getStrippedFileName() + "_" + context.getCompileError().line + "_" + cleanedClassName + "_" + cleanedProviderModelName + ".txt");

        if (!usePromptCaching || !Files.exists(llmResponseFilePath)) {
            String prompt = buildPrompt(brokenCode, errorLocation, context.getIteration());
            System.out.println(prompt);

            try {
                Files.write(promptFilePath, prompt.getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }


            for (int i = 0; result == null && i < 5; i++) {
                try {
                    result = sendAndPrintCode(prompt, context.getConfig().getTemperature(), context.getConfig().getTop_k());
                } catch (AIProviderException e) {
                    e.printStackTrace();
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e2) {
                        throw new RuntimeException(e2);
                    }
                }

            }

            context.getLlmRequests().incrementAndGet();


            try {
                FileOutputStream fileOutputStream = new FileOutputStream(llmResponseFilePath.toFile());
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
                objectOutputStream.writeObject(result);
                objectOutputStream.flush();
                objectOutputStream.close();
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } else {
            try {
                System.out.println("Loading LLM response from stored responses");
                FileInputStream fileInputStream = new FileInputStream(llmResponseFilePath.toFile());
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                result = (ConflictResolutionResult) objectInputStream.readObject();
                objectInputStream.close();
            } catch (FileNotFoundException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return new ProposedChange(context.getStrippedClassName(), result.code(), context.getCompileError().file, brokenCode.start(), brokenCode.end());
    }
}
