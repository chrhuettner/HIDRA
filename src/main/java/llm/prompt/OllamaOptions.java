package llm.prompt;

public class OllamaOptions {
    public int seed;
    public double temperature = 0;
    //public double top_k = 1;

    public OllamaOptions(int seed, double temperature, double top_k) {
        this.seed = seed;
        this.temperature = temperature;
        //this.top_k = top_k;
    }
}
