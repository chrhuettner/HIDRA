package context;

import com.github.dockerjava.api.DockerClient;
import core.BumpConfig;
import llm.WordSimilarityModel;
import dto.PathComponents;
import dto.ProposedChange;
import llm.LLMProvider;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Context {
    private String project;
    private String previousVersion;
    private String newVersion;
    private String dependencyArtifactId;
    private String strippedFileName;
    private File outputDirClasses;

    private LogParser.CompileError compileError;

    private String brokenUpdateImage;
    private Path targetPathOld;
    private Path targetPathNew;

    private String targetDirectoryClasses;
    private File outputDirSrcFiles;

    private LLMProvider activeProvider;
    private DockerClient dockerClient;
    private HashMap<String, ProposedChange> errorSet;
    private List<ProposedChange> proposedChanges;

    private String targetDirectoryLLMResponses;

    private String targetDirectoryPrompts;
    private String targetDirectoryFixedClasses;
    private String targetDirectoryFixedLogs;
    private String strippedClassName;

    private BumpConfig config;
    private WordSimilarityModel wordSimilarityModel;

    private int iteration;

    private HashMap<String, PathComponents> fixedClassesFromPastIterations = new HashMap<>();

    private String targetDirectoryResult;

    private AtomicInteger llmRequests;
    private int retry;


    public Context(String project, String previousVersion, String newVersion, String dependencyArtifactId, String strippedFileName,
                   File outputDirClasses, String brokenUpdateImage, Path targetPathOld, Path targetPathNew, String targetDirectoryClasses,
                   File outputDirSrcFiles, LLMProvider activeProvider, DockerClient dockerClient, HashMap<String, ProposedChange> errorSet,
                   List<ProposedChange> proposedChanges, LogParser.CompileError compileError, String targetDirectoryLLMResponses,
                   String targetDirectoryPrompts, String targetDirectoryFixedClasses, String targetDirectoryFixedLogs, String strippedClassName,
                   BumpConfig config, WordSimilarityModel wordSimilarityModel, String targetDirectoryResult, AtomicInteger llmRequests) {
        this.project = project;
        this.previousVersion = previousVersion;
        this.newVersion = newVersion;
        this.dependencyArtifactId = dependencyArtifactId;
        this.strippedFileName = strippedFileName;
        this.outputDirClasses = outputDirClasses;
        this.brokenUpdateImage = brokenUpdateImage;
        this.targetPathOld = targetPathOld;
        this.targetPathNew = targetPathNew;
        this.targetDirectoryClasses = targetDirectoryClasses;
        this.outputDirSrcFiles = outputDirSrcFiles;
        this.activeProvider = activeProvider;
        this.dockerClient = dockerClient;
        this.errorSet = errorSet;
        this.proposedChanges = proposedChanges;
        this.compileError = compileError;
        this.targetDirectoryLLMResponses = targetDirectoryLLMResponses;
        this.targetDirectoryPrompts = targetDirectoryPrompts;
        this.targetDirectoryFixedClasses = targetDirectoryFixedClasses;
        this.targetDirectoryFixedLogs = targetDirectoryFixedLogs;
        this.strippedClassName = strippedClassName;
        this.config = config;
        this.wordSimilarityModel = wordSimilarityModel;
        this.iteration = 0;
        this.fixedClassesFromPastIterations = new HashMap<>();
        this.targetDirectoryResult = targetDirectoryResult;
        this.llmRequests = llmRequests;
        this.retry = 0;
    }

    public String getStrippedClassName() {
        return strippedClassName;
    }

    public void setStrippedClassName(String strippedClassName) {
        this.strippedClassName = strippedClassName;
    }

    public String getTargetDirectoryFixedClasses() {
        return targetDirectoryFixedClasses;
    }

    public void setTargetDirectoryFixedClasses(String targetDirectoryFixedClasses) {
        this.targetDirectoryFixedClasses = targetDirectoryFixedClasses;
    }

    public String getTargetDirectoryFixedLogs() {
        return targetDirectoryFixedLogs;
    }

    public void setTargetDirectoryFixedLogs(String targetDirectoryFixedLogs) {
        this.targetDirectoryFixedLogs = targetDirectoryFixedLogs;
    }

    public String getTargetDirectoryPrompts() {
        return targetDirectoryPrompts;
    }

    public void setTargetDirectoryPrompts(String targetDirectoryPrompts) {
        this.targetDirectoryPrompts = targetDirectoryPrompts;
    }

    public String getTargetDirectoryLLMResponses() {
        return targetDirectoryLLMResponses;
    }

    public void setTargetDirectoryLLMResponses(String targetDirectoryLLMResponses) {
        this.targetDirectoryLLMResponses = targetDirectoryLLMResponses;
    }

    public LogParser.CompileError getCompileError() {
        return compileError;
    }

    public void setCompileError(LogParser.CompileError compileError) {
        this.compileError = compileError;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getPreviousVersion() {
        return previousVersion;
    }

    public void setPreviousVersion(String previousVersion) {
        this.previousVersion = previousVersion;
    }

    public String getNewVersion() {
        return newVersion;
    }

    public void setNewVersion(String newVersion) {
        this.newVersion = newVersion;
    }

    public String getDependencyArtifactId() {
        return dependencyArtifactId;
    }

    public void setDependencyArtifactId(String dependencyArtifactId) {
        this.dependencyArtifactId = dependencyArtifactId;
    }

    public String getStrippedFileName() {
        return strippedFileName;
    }

    public void setStrippedFileName(String strippedFileName) {
        this.strippedFileName = strippedFileName;
    }

    public File getOutputDirClasses() {
        return outputDirClasses;
    }

    public void setOutputDirClasses(File outputDirClasses) {
        this.outputDirClasses = outputDirClasses;
    }

    public String getBrokenUpdateImage() {
        return brokenUpdateImage;
    }

    public void setBrokenUpdateImage(String brokenUpdateImage) {
        this.brokenUpdateImage = brokenUpdateImage;
    }

    public Path getTargetPathOld() {
        return targetPathOld;
    }

    public void setTargetPathOld(Path targetPathOld) {
        this.targetPathOld = targetPathOld;
    }

    public Path getTargetPathNew() {
        return targetPathNew;
    }

    public void setTargetPathNew(Path targetPathNew) {
        this.targetPathNew = targetPathNew;
    }

    public String getTargetDirectoryClasses() {
        return targetDirectoryClasses;
    }

    public void setTargetDirectoryClasses(String targetDirectoryClasses) {
        this.targetDirectoryClasses = targetDirectoryClasses;
    }

    public File getOutputDirSrcFiles() {
        return outputDirSrcFiles;
    }

    public void setOutputDirSrcFiles(File outputDirSrcFiles) {
        this.outputDirSrcFiles = outputDirSrcFiles;
    }

    public LLMProvider getActiveProvider() {
        return activeProvider;
    }

    public void setActiveProvider(LLMProvider activeProvider) {
        this.activeProvider = activeProvider;
    }

    public DockerClient getDockerClient() {
        return dockerClient;
    }

    public void setDockerClient(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public HashMap<String, ProposedChange> getErrorSet() {
        return errorSet;
    }

    public void setErrorSet(HashMap<String, ProposedChange> errorSet) {
        this.errorSet = errorSet;
    }

    public List<ProposedChange> getProposedChanges() {
        return proposedChanges;
    }

    public void setProposedChanges(List<ProposedChange> proposedChanges) {
        this.proposedChanges = proposedChanges;
    }

    public BumpConfig getConfig() {
        return config;
    }

    public void setConfig(BumpConfig config) {
        this.config = config;
    }

    public WordSimilarityModel getWordSimilarityModel() {
        return wordSimilarityModel;
    }

    public void setWordSimilarityModel(WordSimilarityModel wordSimilarityModel) {
        this.wordSimilarityModel = wordSimilarityModel;
    }

    public int getIteration() {
        return iteration;
    }

    public int getPreviousIteration() {
        return iteration-1;
    }

    public void setIteration(int iteration) {
        this.iteration = iteration;
    }

    public HashMap<String, PathComponents> getFixedClassesFromPastIterations() {
        return fixedClassesFromPastIterations;
    }

    public void setFixedClassesFromPastIterations(HashMap<String, PathComponents> fixedClassesFromPastIterations) {
        this.fixedClassesFromPastIterations = fixedClassesFromPastIterations;
    }

    public String getTargetDirectoryResult() {
        return targetDirectoryResult;
    }

    public void setTargetDirectoryResult(String targetDirectoryResult) {
        this.targetDirectoryResult = targetDirectoryResult;
    }

    public AtomicInteger getLlmRequests() {
        return llmRequests;
    }

    public void setLlmRequests(AtomicInteger llmRequests) {
        this.llmRequests = llmRequests;
    }

    public int getRetry() {
        return retry;
    }

    public void setRetry(int retry) {
        this.retry = retry;
    }
}
