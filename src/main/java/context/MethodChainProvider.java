package context;

import docker.ContainerUtil;
import dto.BrokenCode;
import dto.ErrorLocation;
import dto.MethodCall;
import dto.MethodChainAnalysis;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MethodChainProvider extends BrokenCodeRegexProvider {

    protected MethodChainProvider(Context context) {
        super(context, Pattern.compile("((new|=|\\.)\\s*\\w+)\\s*(?=\\(.*\\))"));
    }

    @Override
    public ErrorLocation getErrorLocation(LogParser.CompileError compileError, BrokenCode brokenCode) {
        MethodChainAnalysis methodChainAnalysis = analyseMethodChain(context.getCompileError().column, brokenCode.start(),
                brokenCode.code(), context.getTargetDirectoryClasses(), context.getStrippedFileName(), context.getStrippedClassName(),
                context.getOutputDirSrcFiles().toPath().resolve(Path.of(context.getDependencyArtifactId() + "_" + context.getStrippedFileName())).toString(), context.getIteration());

        return new ErrorLocation(methodChainAnalysis.targetClass(), methodChainAnalysis.targetMethod(), methodChainAnalysis.parameterTypes());
    }

    public static MethodChainAnalysis analyseMethodChain(int compileErrorColumn, int line, String brokenCode, String targetDirectoryClasses,
                                                         String strippedFileName, String strippedClassName, String srcDirectory, int iteration) {
        int errorIndex = Math.min(compileErrorColumn-1, brokenCode.length() - 1);
        Path classLookupPath = ContainerUtil.getPathWithRespectToIteration(targetDirectoryClasses, strippedFileName, strippedClassName, iteration, true);

        //System.out.println("Trying to create source code analyser");
        SourceCodeAnalyzer sourceCodeAnalyzer = SourceCodeAnalyzer.getInstance(srcDirectory);
        //System.out.println("Sourcecodeanalyzer created!!");
        int oldLength = brokenCode.length();
        if (brokenCode.contains("=")) {
            brokenCode = brokenCode.substring(brokenCode.indexOf("=") + 1).trim();
        }

        boolean isConstructor = false;

        if (brokenCode.trim().startsWith("return")) {
            brokenCode = brokenCode.substring(brokenCode.indexOf("return") + "return".length()).trim();
        }

        if (brokenCode.trim().startsWith("new")) {
            brokenCode = brokenCode.substring(brokenCode.indexOf("new") + "new".length()).trim();
            isConstructor = true;
        }

        errorIndex -= (oldLength - brokenCode.length());


        String targetClass = "";
        String targetMethod = "";

        String[] parameterNames = new String[0];


        int index = 0;
        while (index < brokenCode.length()) {
            int openBraceIndex = brokenCode.indexOf("(", index);
            if (openBraceIndex == -1) {
                break;
            }
            int closingBraceIndex = ContextUtil.getClosingBraceIndex(brokenCode, openBraceIndex);
            if(closingBraceIndex == -1){
                index++;
                continue;
            }
            String potentialInnerChain = brokenCode.substring(openBraceIndex + 1, closingBraceIndex);
            if (!potentialInnerChain.contains("(")) {
                index = closingBraceIndex + 1;
                continue;
            }
            if (errorIndex > openBraceIndex && errorIndex < closingBraceIndex) {
                brokenCode = potentialInnerChain;
                errorIndex -= openBraceIndex;
            } else {
                index++;
            }
        }

        List<MethodCall> methodCalls = new ArrayList<>();
        String methodName = "";

        for (int i = 0; i < brokenCode.length(); i++) {
            char c = brokenCode.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            } else if (c == '.') {
                if (!methodName.isEmpty()) {
                    methodCalls.add(new MethodCall(methodName, "", null));
                    methodName = "";
                }
                continue;
            } else if (c == '(') {
                int closingBraceIndex = ContextUtil.getClosingBraceIndex(brokenCode, i);
                if(closingBraceIndex == -1){
                    break;
                }
                String innard = brokenCode.substring(i + 1, closingBraceIndex);
                String[] parameterTypes = ContextUtil.getParameterTypesOfMethodCall(sourceCodeAnalyzer, methodName + "(" + innard + ")", targetDirectoryClasses,
                        strippedFileName, strippedClassName, line, new File(srcDirectory), classLookupPath,
                        iteration).toArray(new String[]{});
                methodCalls.add(new MethodCall(methodName, innard, parameterTypes));
                methodName = "";
                i = closingBraceIndex;
                continue;
            }

            methodName += c;
        }


        if (methodCalls.size() > 0) {
            String classNameOfVariable;
            if (methodCalls.get(0).methodName().equals("super")) {
                classNameOfVariable = ContainerUtil.readParent(strippedClassName, targetDirectoryClasses, strippedFileName, iteration);
            } else if (methodCalls.get(0).methodName().equals("this")) {
                classNameOfVariable = strippedClassName;
            } else {

                Path classPath = ContainerUtil.getPathWithRespectToIteration(targetDirectoryClasses, strippedFileName, strippedClassName, iteration, true);
                classNameOfVariable = ContextUtil.getClassNameOfVariable(methodCalls.get(0).methodName(), classPath, line);

                if (classNameOfVariable == null) {
                    String parent = ContainerUtil.readParent(strippedClassName, targetDirectoryClasses, strippedFileName, iteration);
                    if (parent != null) {
                        Path parentPath = ContainerUtil.searchForClassInSourceFiles(new File(srcDirectory), parent);
                        if (parentPath != null) {
                            classNameOfVariable = ContextUtil.getClassNameOfVariable(methodCalls.get(0).methodName(), parentPath, Integer.MAX_VALUE);
                        } else {
                            classNameOfVariable = sourceCodeAnalyzer.getTypeOfFieldInClass(new File(srcDirectory + "/tmp/dependencies"), parent, methodCalls.get(0).methodName());

                        }
                    }
                }
            }

            if (classNameOfVariable == null) {
                // Assume its a static call
                classNameOfVariable = methodCalls.get(0).methodName();
                targetClass = methodCalls.get(0).methodName();
                if (isConstructor) {
                    targetMethod = methodCalls.get(0).methodName();
                    parameterNames = methodCalls.get(0).parameterTypes();
                }
            }
            if (methodCalls.size() > 1) {
                if (classNameOfVariable == null) {
                    return null;
                }
                //JarDiffUtil jarDiffUtil = new JarDiffUtil(targetPathOld.toString(), targetPathNew.toString());

                String previousClassName = null;
                String intermediateClassName = classNameOfVariable;
                for (int i = 1; i < methodCalls.size() - 1; i++) {
                    if (intermediateClassName == null) {
                        return new MethodChainAnalysis(previousClassName, methodCalls.get(i - 1).methodName(), methodCalls.get(i - 1).parameterTypes());
                    }
                    previousClassName = intermediateClassName;
                    intermediateClassName = sourceCodeAnalyzer.getReturnTypeOfMethod(intermediateClassName, methodCalls.get(i).methodName(), methodCalls.get(i).parameterTypes());
                }

                if (intermediateClassName == null) {
                    return new MethodChainAnalysis(previousClassName, methodCalls.get(methodCalls.size() - 2).methodName(), methodCalls.get(methodCalls.size() - 2).parameterTypes());
                }

                targetClass = intermediateClassName;
                targetMethod = methodCalls.get(methodCalls.size() - 1).methodName();
                parameterNames = methodCalls.get(methodCalls.size() - 1).parameterTypes();

            }
        }

        return new MethodChainAnalysis(targetClass, targetMethod, parameterNames);
    }
}
