package llm;

import okhttp3.Request;
import llm.prompt.*;

public class AnthropicProvider extends BaseLLMProvider {
    private final String model;
    private String apiKey;

    public AnthropicProvider() {
        model = "claude-opus-3";
    }

    public AnthropicProvider(String model, String apiKey) {
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public Object getPromptWithContext(String prompt, String context, double temperature, double top_k) {
        ClaudeMessage[] messages = new ClaudeMessage[2];
        messages[0] = new ClaudeMessage("system", new ClaudeContent("text", context));
        messages[1] = new ClaudeMessage("user", new ClaudeContent("text", prompt));
        return new ClaudePrompt(model, 1000, 0.8, messages);
    }

    @Override
    public String getUrl() {
        return "https://api.anthropic.com/v1/messages";
    }

    public String getApiKey() {
        return apiKey;
    }

    @Override
    public String extractContentFromResponse(String response) {
        //try {
        //JsonNode jsonNode = mapper.readTree(response);
        return response;//jsonNode.get("choices").get(0).get("message").get("content").asText();
        // } catch (JsonProcessingException e) {
        //    throw new RuntimeException(e);
        // }
    }

    @Override
    public Request.Builder addHeadersToBuilder(Request.Builder builder) {
        return builder.header("x-api-key", getApiKey()).header("anthropic-version", "2023-06-01");
    }

    @Override
    public String getModel() {
        return model;
    }
}
