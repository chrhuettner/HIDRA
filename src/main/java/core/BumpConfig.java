package core;

import java.util.Map;
import java.util.Set;

public class BumpConfig {
    private String pathToBUMPFolder;
    private int threads;
    private int maxIterations;
    private int maxRetries;
    private String pathToOutput;
    private String llmProvider;
    private String ollamaUri;
    private String llmName;
    private String dockerHostUri;
    private String dockerUsername;
    private String dockerPassword;
    private String dockerRegistryUri;
    private String wordSimilarityModel;
    private String llmApiKey;
    private Set<String> disabledPromptComponents;
    private double temperature;
    private double top_k;


    public String getPathToBUMPFolder() {
        return pathToBUMPFolder;
    }

    public void setPathToBUMPFolder(String pathToBUMPFolder) {
        this.pathToBUMPFolder = pathToBUMPFolder;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public String getPathToOutput() {
        return pathToOutput;
    }

    public void setPathToOutput(String pathToOutput) {
        this.pathToOutput = pathToOutput;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getLlmName() {
        return llmName;
    }

    public void setLlmName(String llmName) {
        this.llmName = llmName;
    }

    public String getDockerHostUri() {
        return dockerHostUri;
    }

    public void setDockerHostUri(String dockerHostUri) {
        this.dockerHostUri = dockerHostUri;
    }

    public String getDockerUsername() {
        return dockerUsername;
    }

    public void setDockerUsername(String dockerUsername) {
        this.dockerUsername = dockerUsername;
    }

    public String getDockerPassword() {
        return dockerPassword;
    }

    public void setDockerPassword(String dockerPassword) {
        this.dockerPassword = dockerPassword;
    }

    public String getDockerRegistryUri() {
        return dockerRegistryUri;
    }

    public void setDockerRegistryUri(String dockerRegistryUri) {
        this.dockerRegistryUri = dockerRegistryUri;
    }

    public String getOllamaUri() {
        return ollamaUri;
    }

    public void setOllamaUri(String ollamaUri) {
        this.ollamaUri = ollamaUri;
    }

    public String getWordSimilarityModel() {
        return wordSimilarityModel;
    }

    public void setWordSimilarityModel(String wordSimilarityModel) {
        this.wordSimilarityModel = wordSimilarityModel;
    }

    public String getLlmApiKey() {
        return llmApiKey;
    }

    public void setLlmApiKey(String llmApiKey) {
        this.llmApiKey = llmApiKey;
    }

    public Set<String> getDisabledPromptComponents() {
        return disabledPromptComponents;
    }

    public void setDisabledPromptComponents(Set<String> disabledPromptComponents) {
        this.disabledPromptComponents = disabledPromptComponents;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public double getTop_k() {
        return top_k;
    }

    public void setTop_k(double top_k) {
        this.top_k = top_k;
    }
}
