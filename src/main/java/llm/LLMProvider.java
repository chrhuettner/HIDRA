package llm;

import dto.ConflictResolutionResult;

public interface LLMProvider {

    ConflictResolutionResult sendPromptAndReceiveResponse(String prompt, String context, double temperature, String think);

    String getModel();
}
