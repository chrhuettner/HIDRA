package llm;

import java.io.IOException;
import java.net.http.*;
import java.net.URI;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.databind.*;

public class WordSimilarityModel {

    private String model;
    private static final String OLLAMA_URL_SUFFIX = "/api/embeddings";
    private String ollamaUrl;

    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

    private static final Semaphore HTTP_LIMITER = new Semaphore(8, true);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WordSimilarityModel(String model, String ollamaUrl) {
        this.model = model;
        this.ollamaUrl = ollamaUrl+OLLAMA_URL_SUFFIX;
    }

    public double[] getEmbedding(String word)  {
        //System.out.println("Attempting to get embedding for: "+word);
        String json = String.format(
                "{\"model\":\"%s\",\"prompt\":\"%s\"}",
                model,
                word
        );
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
        }catch (IllegalArgumentException e){
            throw new RuntimeException(e);
        }

        JsonNode root = null;
        try {
            HTTP_LIMITER.acquire();

            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            root = MAPPER.readTree(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP call interrupted", e);

        } catch (IOException e) {
            throw new RuntimeException("HTTP call failed", e);

        } finally {
            HTTP_LIMITER.release();
        }


        JsonNode emb = root.get("embedding");
        double[] vec = new double[emb.size()];
        for (int i = 0; i < emb.size(); i++) {
            vec[i] = emb.get(i).asDouble();
        }
        return vec;
    }

    public static double cosineSimilarity(double[] a, double[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
