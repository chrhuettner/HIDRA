package llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Request;
import llm.prompt.OllamaMessage;
import llm.prompt.OllamaOptions;
import llm.prompt.OllamaPrompt;

public class OllamaProvider extends BaseLLMProvider {
    private final String model;
    private String url;

    public OllamaProvider() {
        model = "deepseek-r1:7b";
    }

    public OllamaProvider(String model, String url) {
        this.model = model;
        this.url = url;
    }

    @Override
    public Object getPromptWithContext(String prompt, String context, double temperature, double top_k) {
        OllamaMessage[] messages = new OllamaMessage[2];
        messages[0] = new OllamaMessage("system", context);
        messages[1] = new OllamaMessage("user", prompt);
        return new OllamaPrompt(model, messages, false, new OllamaOptions(1, temperature, top_k));
    }

    @Override
    public String getUrl() {
        return url;
    }

    @Override
    public String extractContentFromResponse(String response) {
        try {
            JsonNode jsonNode = mapper.readTree(response);
            return jsonNode.get("message").get("content").asText();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Request.Builder addHeadersToBuilder(Request.Builder builder) {
        // No further headers needed for local Ollama
        return builder;
    }

    @Override
    public String getModel() {
        return model;
    }
}
