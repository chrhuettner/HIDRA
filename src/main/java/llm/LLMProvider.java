package llm;

import dto.ConflictResolutionResult;

public interface LLMProvider {

    ConflictResolutionResult sendPromptAndReceiveResponse(String prompt, String context, double temperature, double top_k);

    String getModel();
}
