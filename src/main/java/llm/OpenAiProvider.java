package llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Request;
import llm.prompt.ChatGPTMessage;
import llm.prompt.ChatGPTPrompt;

public class OpenAiProvider extends BaseLLMProvider {
    private final String model;
    private String apiKey;

    public OpenAiProvider() {
        model = "gpt-4o-mini";
    }

    public OpenAiProvider(String model, String apiKey) {
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public Object getPromptWithContext(String prompt, String context, double temperature, String think) {
        ChatGPTMessage[] messages = new ChatGPTMessage[2];
        messages[0] = new ChatGPTMessage("system", context);
        messages[1] = new ChatGPTMessage("user", prompt);
        return new ChatGPTPrompt(model, messages);
    }

    @Override
    public String getUrl() {
        return "https://api.openai.com/v1/chat/completions";
    }

    public String getApiKey() {
        return apiKey;
    }

    @Override
    public String extractContentFromResponse(String response) {
        try {
            JsonNode jsonNode = mapper.readTree(response);
            return jsonNode.get("choices").get(0).get("message").get("content").asText();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Request.Builder addHeadersToBuilder(Request.Builder builder) {
        return builder.header("Authorization", "Bearer " + getApiKey());
    }

    @Override
    public String getModel() {
        return model;
    }
}
