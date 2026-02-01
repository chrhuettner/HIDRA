package dto;

import java.util.Arrays;

public record ErrorLocation(String className, String methodName, String[] targetMethodParameterClassNames) {
    @Override
    public String toString() {
        return "ErrorLocation[" +
                "className=" + className +
                ", methodName=" + methodName +
                ", targetMethodParameterClassNames=" +
                Arrays.toString(targetMethodParameterClassNames) +
                "]";
    }
}