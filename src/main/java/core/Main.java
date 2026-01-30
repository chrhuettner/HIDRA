package core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dependencysimilarity.DependencyAnalyser;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;

public class Main {
    public static void main(String[] args) {
        if(args.length < 2) {
            printGuide();
        }else if(args[0].equalsIgnoreCase("bump")) {
            if(args.length > 2) {
                System.out.println("Input after folder path gets ignored!");
            }
            String pathToBump = args[1];

            ObjectMapper mapper = new ObjectMapper();

            BumpConfig config = null;
            try {
                config = mapper.readValue(new File(pathToBump), BumpConfig.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("Read config from "+pathToBump);
            System.out.println("------------------------------");
            System.out.println("BUMP folder: " + config.getPathToBUMPFolder());
            System.out.println("Threads: " + config.getThreads());
            System.out.println("Max Retries: " + config.getMaxRetries());
            System.out.println("Max Iterations: " + config.getMaxIterations());
            System.out.println("Output Path: " + config.getPathToOutput());
            System.out.println("LLM provider: " + config.getLlmProvider());
            System.out.println("LLM name: " + config.getLlmName());
            System.out.println("LLM temperature: " + config.getTemperature());
            System.out.println("Docker host: " + config.getDockerHostUri());
            System.out.println("Docker registry: " + config.getDockerRegistryUri());
            System.out.println("Word Similarity Model: " + config.getWordSimilarityModel());
            if(config.getDisabledPromptComponents() == null){
                config.setDisabledPromptComponents(new HashSet<>());
            }
            System.out.println("Disabled Prompt Components: " + config.getDisabledPromptComponents());

            System.out.println("------------------------------");
            System.out.println("Starting code-related dependency conflict solving");



            BumpRunner.runBUMP(config);

        } else if(args[0].equalsIgnoreCase("dependency")) {
            if(args.length < 4) {
                printGuide();
                return;
            }

            String dependencyUri = args[1];
            String ollamaUri = args[2];
            String model = args[3];
            DependencyAnalyser.analyseDependencySimilarity(dependencyUri, ollamaUri, model);

        }else{
            printGuide();
        }
    }

    private static void printGuide(){
        System.err.println("Usage: bump <pathToConfigFile>");
    }
}