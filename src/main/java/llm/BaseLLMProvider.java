package llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.ConflictResolutionResult;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public abstract class BaseLLMProvider implements LLMProvider {

    public static String codeStart = "---BEGIN UPDATED java CODE---";
    public static String codeEnd = "---END UPDATED java CODE---";

    protected final OkHttpClient client;
    protected final ObjectMapper mapper;

    private static final Semaphore LLM_SEMAPHORE = new Semaphore(2, true);

    public BaseLLMProvider() {
        client = new OkHttpClient.Builder().connectTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .callTimeout(330, TimeUnit.SECONDS)
                .build();
        mapper = new ObjectMapper();
    }

    @Override
    public ConflictResolutionResult sendPromptAndReceiveResponse(String prompt, String context, double temperature, double top_k) {
        String json;
        try {
            json = mapper.writeValueAsString(getPromptWithContext(prompt, context, temperature, top_k));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        try {
            LLM_SEMAPHORE.acquire();

            Request request = buildRequestFromJsonString(json);

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response + ": " + response.body().string());
                }
                if (response.body() == null) {
                    throw new IOException("Empty response");
                }
                String result = extractContentFromResponse(response.body().string());
                int startIndex = result.indexOf(codeStart);
                int endIndex = result.indexOf(codeEnd);
                String code = "";

                if (startIndex != -1 && endIndex != -1) {
                    code = result.substring(startIndex + codeStart.length(), endIndex).trim();
                } else {
                    System.err.println(getModel() + " failed to respond code in expected format!");
                    System.err.println("Full response: " + result);
                }

                return new ConflictResolutionResult(code, result);
            } catch (IOException e) {
                throw new AIProviderException(e);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            LLM_SEMAPHORE.release();
        }
    }

    protected Request buildRequestFromJsonString(String json) {
        RequestBody body = RequestBody.create(
                json, MediaType.parse("application/json"));

        Request.Builder baseRequest = new Request.Builder()
                .url(getUrl())
                .post(body);

        return addHeadersToBuilder(baseRequest).build();
    }

    public abstract Object getPromptWithContext(String prompt, String context, double temperature, double top_k);

    public abstract String getUrl();

    public abstract String extractContentFromResponse(String response);

    public abstract Request.Builder addHeadersToBuilder(Request.Builder builder);

    public static LLMProvider getProviderByNames(String providerName, String modelName, String ollamaUrl, String apiKey) {
        switch (providerName) {
            case "ollama":
                return new OllamaProvider(modelName, ollamaUrl + "/api/chat");
            case "openai":
                return new OpenAiProvider(modelName, apiKey);
            case "anthropic":
                return new AnthropicProvider(modelName, apiKey);
            default:
                throw new IllegalArgumentException("Unknown provider name: " + providerName + ". Valid options: ollama, openai, anthropic");
        }
    }
}
