package context;

import docker.ContainerUtil;
import dto.CleanedLines;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ContextUtil {

    private static final Logger log = LoggerFactory.getLogger(ContextUtil.class);

    //TODO: Improve this regex so it only allows primitives (and Strings), currently it also allows variables inside the expressions
    private static final Pattern EXPRESSION_PATTERN =  Pattern.compile(".*[+\\-*/%&|^~<>].*");

    public static int getClosingBraceIndex(String s, int start) {
        int openBrackets = 0;
        int closedBrackets = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                openBrackets++;
            } else if (c == ')') {
                closedBrackets++;
            }

            if (openBrackets == closedBrackets && openBrackets != 0) {
                return i;
            }
        }
        return -1;
    }

    public static List<Integer> getOuterParameterSeparators(String s, int start) {
        int openBrackets = 0;
        int closedBrackets = 0;
        List<Integer> parameterSeparators = new ArrayList<>();
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                openBrackets++;
            } else if (c == ')') {
                closedBrackets++;
            }

            if (openBrackets == closedBrackets && c == ',') {
                parameterSeparators.add(i);
            }
        }
        return parameterSeparators;
    }

    public static String getTypeOfField(SourceCodeAnalyzer sourceCodeAnalyzer, String callerVariable, String fieldChain, File srcDirectory, Path classLookup, int lineNumber) {

        if (fieldChain.equals("class")) {
            return "java.lang.Class";
        }

        String caller = getClassNameOfVariable(callerVariable, classLookup, lineNumber);
        if (caller == null) {
            //Assume static
            caller = callerVariable;
        }
        String potentialInnerChain = fieldChain.substring(fieldChain.indexOf(".") + 1);
        String fieldName = "";
        boolean isRecursive = false;
        if (potentialInnerChain.contains(".")) {
            fieldName = potentialInnerChain.substring(0, potentialInnerChain.indexOf("."));
            isRecursive = true;
        } else {
            fieldName = potentialInnerChain;
        }

        String classNameOfField = sourceCodeAnalyzer.getTypeOfFieldFromSourceCode(caller, fieldName);
        if (classNameOfField == null) {
            classNameOfField = sourceCodeAnalyzer.getTypeOfFieldInClass(new File(srcDirectory.toPath().resolve(Path.of("tmp/dependencies")).toUri()), caller, fieldName);
        }
        if (isRecursive) {
            String residualChain = potentialInnerChain.substring(potentialInnerChain.indexOf("."));
            return getTypeOfField(sourceCodeAnalyzer, classNameOfField, residualChain, srcDirectory, classLookup, lineNumber);
        }
        return classNameOfField;
    }

    public static CleanedLines cleanLines(List<String> lines, int indexBeforeCleaning) {
        List<String> newLines = new ArrayList<>();
        int indexAfterCleaning = indexBeforeCleaning;

        boolean lastCharWasSlash = false;
        boolean lastCharWasStar = false;
        boolean isInComment = false;
        for (int j = 0; j < lines.size(); j++) {
            String line = lines.get(j);
            if (line.startsWith("//")) {
                if (j < indexBeforeCleaning) {
                    indexAfterCleaning--;
                }
                continue;
            }
            int commentStart = -1, commentEnd = -1;
            if (line.contains("/*") || line.trim().endsWith("/")) {
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c == '/') {
                        lastCharWasSlash = true;
                        if (isInComment && lastCharWasStar) {
                            isInComment = false;
                            commentEnd = i;
                        }
                    } else if (c == '*') {
                        lastCharWasStar = true;
                        if (!isInComment && lastCharWasSlash) {
                            isInComment = true;
                            commentStart = i - 1;
                        }
                    } else {
                        lastCharWasSlash = false;
                        lastCharWasStar = false;
                    }
                }
            }

            if (isInComment || commentEnd != -1 || commentStart != -1) {
                String lineWithoutComments = "";
                if (commentStart != -1) {
                    lineWithoutComments += line.substring(0, commentStart);
                }
                if (commentEnd != -1) {
                    lineWithoutComments += line.substring(commentEnd + 1);
                }
                if (!lineWithoutComments.isBlank()) {
                    newLines.add(lineWithoutComments);
                } else {
                    if (j < indexBeforeCleaning) {
                        indexAfterCleaning--;
                    }
                }
                continue;
            }

            newLines.add(line);
        }

        return new CleanedLines(newLines, indexBeforeCleaning, indexAfterCleaning);
    }

public static String getClassNameOfVariable(String variableName, Path path, int lineNumber) {
    if (variableName == null) {
        return null;
    }

    if (variableName.startsWith("\"") && variableName.endsWith("\"")) {
        return String.class.getName();
    }

    if (variableName.equals("true") || variableName.equals("false")) {
        return Boolean.class.getName();
    }

    Matcher integerMatcher = Pattern.compile("[-+]?\\d+$").matcher(variableName);
    if (integerMatcher.find()) {
        return Integer.class.getName();
    }

    Matcher longMatcher = Pattern.compile("[-+]?\\d+[lL]$").matcher(variableName);
    if (longMatcher.find()) {
        return Long.class.getName();
    }

    Matcher doubleMatcher = Pattern.compile("[-+]?\\d*\\.?\\d+$").matcher(variableName);
    if (doubleMatcher.find()) {
        return Double.class.getName();
    }

    Matcher floatMatcher = Pattern.compile("[-+]?\\d*\\.?\\d+[fF]$").matcher(variableName);
    if (floatMatcher.find()) {
        return Float.class.getName();
    }

    System.out.println("Not a constant: " + variableName);

    if (variableName.contains("->")) {
        return Predicate.class.getName();
    }

    Matcher expressionMatcher = EXPRESSION_PATTERN.matcher(variableName);
    if (expressionMatcher.find()) {
        String expressionType = PrimitiveExpressionSolver.getTypeOfPrimitiveExpression(variableName);
        if(expressionType != null) {
            return expressionType;
        }
    }

    try {
        List<String> allLines = Files.readAllLines(path);

        CleanedLines cleanedLines = cleanLines(allLines, lineNumber);

        allLines = cleanedLines.lines();
        Pattern declarationPattern;
        try {
            declarationPattern = Pattern.compile(
                    "\\b([A-Za-z_][A-Za-z0-9_]*)\\s+(" + variableName + ")\\s*[,|;|=|\\)]");
        }catch (PatternSyntaxException e){
            System.err.println("Invalid declaration pattern: " + e.getMessage());
            return null;
        }
        for (int i = Math.min(cleanedLines.indexAfterCleaning(), allLines.size() - 1); i >= 0; i--) {
            String line = allLines.get(i);
            int variableIndex = line.indexOf(variableName);
            if (variableIndex > 0) {
                Matcher declarationMatcher = declarationPattern.matcher(line);
                if (declarationMatcher.find()) {
                    String match = declarationMatcher.group(1);
                    if (match.trim().equals("new")) {
                        //TODO: Is there any case where this is true?
                        return null;
                    }
                    return match;
                }
            }

        }

    } catch (IOException e) {
        throw new RuntimeException(e);
    }
    return null;
}

    public static List<String> getParameterTypesOfMethodCall(SourceCodeAnalyzer sourceCodeAnalyzer, String methodCall,
                                                             String targetDirectoryClasses, String strippedFileName,
                                                             String strippedClassName, int line, File srcDirectory, Path classLookup,
                                                             int iteration) {
        List<String> parameterTypes = new ArrayList<>();

        if (methodCall.indexOf("(") == methodCall.indexOf(")") - 1) {
            return parameterTypes;
        }
        Path pathToClass = ContainerUtil.getPathWithRespectToIteration(targetDirectoryClasses, strippedFileName, strippedClassName, iteration, true);


        int closingBraceIndex = getClosingBraceIndex(methodCall, methodCall.indexOf("("));
        String potentialInnerChain = methodCall.substring(methodCall.indexOf("(") + 1, closingBraceIndex);

        if (potentialInnerChain.contains("->")) {
            // Package name of lambda functions
            parameterTypes.add("java.util.function");
            return parameterTypes;
        }

        String[] paramSplit = potentialInnerChain.split(",");
        for (String param : paramSplit) {
            param = param.trim();

            Matcher expressionMatcher = EXPRESSION_PATTERN.matcher(param);

            if(expressionMatcher.find()) {
                System.out.println("Expression detected in method call: " + methodCall+" Filename: "+strippedFileName);
            }

            if (!param.contains("(")) {
                if (param.contains(".")) {
                    String callerVariable = param.substring(0, param.indexOf("."));
                    String fieldChain = param.substring(param.indexOf(".") + 1);
                    parameterTypes.add(getTypeOfField(sourceCodeAnalyzer, callerVariable, fieldChain, srcDirectory, classLookup, line));

                } else {
                    parameterTypes.add(getClassNameOfVariable(param, pathToClass, line));

                }
            } else {
                String methodName = param.substring(0, param.indexOf("("));
                List<String> innerTypes = getParameterTypesOfMethodCall(sourceCodeAnalyzer, potentialInnerChain, targetDirectoryClasses, strippedFileName, strippedClassName, line, srcDirectory, classLookup, iteration);
                String className = "";
                if (methodName.contains(".")) {
                    String variableName = methodName.substring(0, methodName.indexOf("."));
                    className = getClassNameOfVariable(variableName, pathToClass, line);
                    if (className == null) {
                        // Assume static invocation
                        className = variableName;
                    }

                    methodName = methodName.substring(methodName.indexOf(".") + 1);
                }

                parameterTypes.add(sourceCodeAnalyzer.getReturnTypeOfMethod(className, methodName, innerTypes.toArray(new String[innerTypes.size()])));
            }

        }


        return parameterTypes;
    }

    public static String primitiveClassNameToWrapperName(String parameter) {
        if (parameter == null) {
            return "NULL";
        }
        switch (parameter) {
            case "boolean":
                return Boolean.class.getName();
            case "int":
                return Integer.class.getName();
            case "double":
                return Double.class.getName();
            case "float":
                return Float.class.getName();
            case "byte":
                return Byte.class.getName();
            case "short":
                return Short.class.getName();
            case "Long":
                return Long.class.getName();
            case "char":
                return Character.class.getName();
            case "String":
                return String.class.getName();
            default:
                return parameter;
        }
    }

    public static String wrapperNameToPrimitiveClassName(String parameter) {

        switch (parameter) {
            case "java.lang.Double": return "double";
            case "java.lang.Float": return "float";
            case "java.lang.Long": return "long";
            case "java.lang.Integer": return "int";
            case "java.lang.Character": return "char";
            case "java.lang.Short": return "short";
            case "java.lang.Byte": return "byte";
            default:
                return parameter;
        }
    }

    public static Object wrapperNameToPrimitiveInstance(String parameter) {
        switch (parameter) {
            case "java.lang.Double": return 0d;
            case "java.lang.Float": return 0f;
            case "java.lang.Long": return 0l;
            case "java.lang.Integer": return 0;
            case "java.lang.Character": return ' ';
            case "java.lang.Short": return (short)0;
            case "java.lang.Byte": return (byte)0;
            default:
                throw new RuntimeException("Unknown primitive type: " + parameter);
        }
    }

    public static boolean parameterIsPrimitiveNumber(String parameterClassName){
        String classWrapper = primitiveClassNameToWrapperName(parameterClassName);
        switch (classWrapper){
            case "java.lang.Double":
            case "java.lang.Float":
            case "java.lang.Long":
            case "java.lang.Integer":
            case "java.lang.Character":
            case "java.lang.Short":
            case "java.lang.Byte":
                return true;
        }
        return false;
    }
}
