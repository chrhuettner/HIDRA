package llm.prompt;

public class OllamaOptions {
    public int seed;
    public double temperature = 0;
    public String think;
    //public double top_k = 1;

    public OllamaOptions(int seed, double temperature, String think) {
        this.seed = seed;
        this.temperature = temperature;
        this.think = think;
        //this.top_k = top_k;
    }
}
