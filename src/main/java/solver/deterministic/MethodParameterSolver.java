package solver.deterministic;

import context.*;
import core.JarDiffUtil;
import docker.ContainerUtil;
import dto.BrokenCode;
import dto.ErrorLocation;
import dto.ProposedChange;
import japicmp.model.JApiChangeStatus;
import japicmp.model.JApiClass;
import japicmp.model.JApiMethod;
import japicmp.model.JApiParameter;
import javassist.CtMethod;
import javassist.NotFoundException;
import solver.ContextAwareSolver;
import type.ConflictType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static japicmp.model.JApiChangeStatus.*;

public class MethodParameterSolver extends ContextAwareSolver {
    public MethodParameterSolver(Context context) {
        super(context);
    }

    @Override
    public boolean errorIsTargetedBySolver(LogParser.CompileError compileError, BrokenCode brokenCode, ErrorLocation errorLocation, List<ConflictType> conflictTypes) {
        return conflictTypes.contains(ConflictType.METHOD_PARAMETERS_ADDED) || conflictTypes.contains(ConflictType.METHOD_PARAMETERS_REMOVED) || conflictTypes.contains(ConflictType.METHOD_PARAMETER_TYPES_CHANGED);
    }

    @Override
    public ProposedChange solveConflict(LogParser.CompileError compileError, BrokenCode brokenCode, ErrorLocation errorLocation) {
        JarDiffUtil jarDiffUtil = JarDiffUtil.getInstance(context.getTargetPathOld().toString(), context.getTargetPathNew().toString());

        JApiClass jApiClass = jarDiffUtil.getClassByName(errorLocation.className());
        if(jApiClass == null){
            return null;
        }
        JApiMethod jApiMethod = jarDiffUtil.getMethodOfClass(jApiClass, errorLocation.methodName(), errorLocation.targetMethodParameterClassNames());

        List<Integer> parametersToRemove = new ArrayList<>();
        HashMap<Integer, String> parametersToAdd = new HashMap<>();
        HashMap<Integer, String[]> parametersToCast = new HashMap<>();

        for (int i = 0; i < jApiMethod.getParameters().size(); i++) {
            JApiParameter jApiParameter = jApiMethod.getParameters().get(i);

            if (jApiParameter.getChangeStatus() == REMOVED) {
                parametersToRemove.add(i);
            } else if (jApiParameter.getChangeStatus() == NEW) {
                parametersToAdd.put(i, jApiParameter.getType());
            } else if (jApiParameter.getChangeStatus() == MODIFIED) {
                CtMethod oldMethod = jApiMethod.getOldMethod().orElse(null);
                if (oldMethod != null) {
                    try {
                        parametersToCast.put(i, new String[]{oldMethod.getParameterTypes()[i].getName(), jApiParameter.getType()});
                    } catch (NotFoundException e) {
                        throw new RuntimeException(e);
                    }

                }
            } else {
                if (errorLocation.targetMethodParameterClassNames().length > i) {
                    if (!SourceCodeAnalyzer.parameterIsCompatibleWithType(errorLocation.targetMethodParameterClassNames()[i], jApiParameter.getType())) {
                        parametersToCast.put(i, new String[]{errorLocation.targetMethodParameterClassNames()[i], jApiParameter.getType()});
                    }
                }
            }

        }

        String methodChain = brokenCode.code().trim();
        int methodInvocationStart = methodChain.indexOf(errorLocation.methodName());
        if (methodInvocationStart == -1) {
            throw new RuntimeException(errorLocation.methodName() + " not found in " + brokenCode.code());
        }
        int methodInvocationEnd = ContextUtil.getClosingBraceIndex(methodChain, methodInvocationStart);
        if (methodInvocationEnd == -1) {
            throw new RuntimeException(errorLocation.methodName() + " has no closing brace in " + brokenCode.code());
        }

        int parameterStart = methodInvocationStart + errorLocation.methodName().length() + 1;
        String methodInvocation = methodChain.substring(parameterStart, methodInvocationEnd);
        List<Integer> separationIndices = ContextUtil.getOuterParameterSeparators(methodInvocation, 0);
        List<String> parameters = new ArrayList<>();
        int lastIndex = 0;
        for (int j = 0; j < separationIndices.size(); j++) {
            parameters.add(methodInvocation.substring(lastIndex, separationIndices.get(j)));
            lastIndex = separationIndices.get(j) + 1;
        }
        parameters.add(methodInvocation.substring(lastIndex));


        for (int index : parametersToRemove) {
            parameters.set(index, null);
        }

        for (int key : parametersToAdd.keySet()) {
            String parameterTypeToAdd = parametersToAdd.get(key);
            if (!ContextUtil.parameterIsPrimitiveNumber(parameterTypeToAdd)) {
                if (parameterTypeToAdd.equals("String")) {
                    parameters.set(key, "");
                } else {
                    return null;
                }
            } else {
                parameters.add(key, "(" + ContextUtil.wrapperNameToPrimitiveInstance(parameterTypeToAdd) + ")" + parameters.get(key));
            }
        }

        for (int key : parametersToCast.keySet()) {
            String[] castFromTo = parametersToCast.get(key);
            if (!ContextUtil.parameterIsPrimitiveNumber(castFromTo[0]) && ContextUtil.parameterIsPrimitiveNumber(castFromTo[1])) {
                return null;
            }

            parameters.set(key, "(" + ContextUtil.wrapperNameToPrimitiveClassName(castFromTo[1]) + ")" + parameters.get(key));
        }

        for (int i = parameters.size() - 1; i >= 0; i--) {
            if(parameters.get(i) == null) {
                parameters.remove(i);
            }
        }


        return new ProposedChange(context.getStrippedClassName(), reconstructMethodChain(methodChain, parameterStart, methodInvocationEnd, parameters.toArray(new String[]{})), context.getCompileError().file, brokenCode.start(), brokenCode.end());
    }

    public String reconstructMethodChain(String originalChain, int invocationStart, int invocationEnd, String[] parameters) {
        String newMethodChain = originalChain.substring(0, invocationStart);
        for (int i = 0; i < parameters.length; i++) {
            newMethodChain = newMethodChain + parameters[i];
            if (i != parameters.length - 1) {
                newMethodChain = newMethodChain + ", ";
            }
        }
        newMethodChain = newMethodChain + originalChain.substring(invocationEnd);

        return newMethodChain;
    }
}
