package core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import context.Context;
import context.ErrorLocationProvider;
import context.LogParser;
import context.SourceCodeAnalyzer;
import docker.ContainerUtil;
import dto.*;
import llm.*;
import solver.CodeConflictSolver;
import type.ConflictType;
import type.TypeProvider;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static context.LogParser.parseLog;
import static context.LogParser.projectIsFixableThroughCodeModification;

public class BumpRunner {

    public static boolean usePromptCaching = false;

    public static ConcurrentHashMap<ConflictType, AtomicInteger> conflicts = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, AtomicInteger> errorsAssignedToSolvers = new ConcurrentHashMap<>();


    // for example: GitHub.connect().getRepository(ghc.owner + '/' + ghc.repo).getCompare(branch, ghc.hash).status;
    // public static final Pattern METHOD_CHAIN_PATTERN = Pattern.compile(
    //         "\\.?([A-Za-z_]\\w*)\\s*(?:\\([^()]*\\))?");

    // private static final Pattern METHOD_CHAIN_DETECTION_PATTERN = Pattern.compile(
    //         "((new|=|\\.)\\s*\\w+)\\s*(?=\\(.*\\))");

    // For example: [ERROR] /lithium/src/main/java/com/wire/lithium/Server.java:[160,16] cannot access io.dropwizard.core.setup.Environment
    //private static final Pattern CLASS_FILE_NOT_FOUND_PATTERN = Pattern.compile(
    //        "cannot access (\\S*)");

    // For example:   class file for io.dropwizard.core.setup.Environment not found
    // private static final Pattern CLASS_FILE_NOT_FOUND_DETAIL_PATTERN = Pattern.compile(
    //         "class file for (\\S*) not found");

    public static final int REFINEMENT_LIMIT = 1;

    // public static List<ContextProvider> contextProviders = new ArrayList<>();
    // public static List<CodeConflictSolver> codeConflictSolvers = new ArrayList<>();

    private static void createFolder(String path) {
        new File(path).mkdirs();
    }

    private static void createBaseFolders(String basePath) {
        createFolder(basePath + "/downloaded");
        createFolder(basePath + "/projectSources");
        createFolder(basePath + "/brokenClasses");
        createFolder(basePath + "/correctedClasses");
        createFolder(basePath + "/correctedLogs");
        createFolder(basePath + "/prompts");
        createFolder(basePath + "/LLMResponses");
        createFolder(basePath + "/oldContainerLogs");
        createFolder(basePath + "/brokenLogs");
        createFolder(basePath + "/result");
    }

    private static void createIterationFolders(String basePath, int iteration) {
        createFolder(basePath + "/correctedClasses/iteration_" + iteration);
        createFolder(basePath + "/correctedLogs/iteration_" + iteration);
        createFolder(basePath + "/prompts/iteration_" + iteration);
        createFolder(basePath + "/LLMResponses/iteration_" + iteration);
        //createFolder(basePath + "/brokenClasses/iteration_" + iteration);
    }

    public static void runBUMP(BumpConfig bumpConfig) {
        createBaseFolders(bumpConfig.getPathToOutput());
        createIterationFolders(bumpConfig.getPathToOutput(), 0);
        String targetDirectory = bumpConfig.getPathToOutput() + "/downloaded";
        File outputDir = new File(targetDirectory);
        File outputDirSrcFiles = new File(bumpConfig.getPathToOutput() + "/projectSources");
        String targetDirectoryClasses = bumpConfig.getPathToOutput() + "/brokenClasses";
        String targetDirectoryFixedClasses = bumpConfig.getPathToOutput() + "/correctedClasses";
        String targetDirectoryFixedLogs = bumpConfig.getPathToOutput() + "/correctedLogs";
        String targetDirectoryPrompts = bumpConfig.getPathToOutput() + "/prompts";
        String targetDirectoryLLMResponses = bumpConfig.getPathToOutput() + "/LLMResponses";
        String directoryOldContainerLogs = bumpConfig.getPathToOutput() + "/oldContainerLogs";
        String targetDirectoryResult = bumpConfig.getPathToOutput() + "/result";


        File outputDirClasses = new File(targetDirectoryClasses);

        LLMProvider activeProvider = BaseLLMProvider.getProviderByNames(bumpConfig.getLlmProvider(), bumpConfig.getLlmName(), bumpConfig.getOllamaUri(), bumpConfig.getLlmApiKey());

        WordSimilarityModel wordSimilarityModel = new WordSimilarityModel(bumpConfig.getWordSimilarityModel(), bumpConfig.getOllamaUri());

        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(bumpConfig.getDockerHostUri())
                .withRegistryUrl(bumpConfig.getDockerRegistryUri())
                .withRegistryUsername(bumpConfig.getDockerUsername())
                .withRegistryPassword(bumpConfig.getDockerPassword())
                .build();

        DockerHttpClient dockerHttpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(1000)
                .connectionTimeout(Duration.ofSeconds(3000000))
                .responseTimeout(Duration.ofSeconds(4500000))
                .build();

        DockerClient dockerClient = DockerClientImpl.getInstance(config, dockerHttpClient);

        File bumpFolder = new File(bumpConfig.getPathToBUMPFolder());
        ObjectMapper objectMapper = new ObjectMapper();

        AtomicInteger satisfiedConflictPairs = new AtomicInteger();
        AtomicInteger totalPairs = new AtomicInteger();
        List<Thread> activeThreads = new ArrayList<>();
        AtomicInteger activeThreadCount = new AtomicInteger();
        AtomicInteger failedFixes = new AtomicInteger();
        AtomicInteger successfulFixes = new AtomicInteger();
        AtomicInteger fixableProjects = new AtomicInteger();
        AtomicInteger imposterProjects = new AtomicInteger();
        AtomicInteger llmRequests = new AtomicInteger();
        List<Double> successfulDurations = new ArrayList<>();
        List<Double> successfulIterations = new ArrayList<>();
        List<Double> successfulRetries = new ArrayList<>();
        List<String> successfulProjectIds = new ArrayList<>();
        ConcurrentHashMap<String, List<Long>> durationPerIteration = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, List<Integer>> retryStartedAtIteration = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, List<HashMap<String, CallDetails>>> solverCallsPerIteration = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, List<HashMap<String, CallDetails>>> providerCallsPerIteration = new ConcurrentHashMap<>();

        int limit = bumpConfig.getThreads();
        long globalStartTime = System.currentTimeMillis();
        for (File file : bumpFolder.listFiles()) {
            while (activeThreadCount.getAndUpdate(operand -> {
                if (operand >= limit) {
                    return operand;
                }
                return operand + 1;
            }) >= limit) {
                try {
                    //System.out.println(activeThreadCount.get());
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            Thread virtualThread = Thread.ofVirtual().start(() -> {
                try {
                    long timeStart = System.currentTimeMillis();
                    JsonNode jsonNode = objectMapper.readTree(file);
                    JsonNode updatedDependency = jsonNode.get("updatedDependency");

                    String mavenSourceLinkPre = cleanString(updatedDependency.get("mavenSourceLinkPre").toString());
                    String mavenSourceLinkBreaking = cleanString(updatedDependency.get("mavenSourceLinkBreaking").toString());

                    if (mavenSourceLinkPre != null && mavenSourceLinkPre.endsWith("-sources.jar")) {
                        mavenSourceLinkPre = mavenSourceLinkPre.replace("-sources.jar", ".jar");
                    }

                    if (mavenSourceLinkBreaking != null && mavenSourceLinkBreaking.endsWith("-sources.jar")) {
                        mavenSourceLinkBreaking = mavenSourceLinkBreaking.replace("-sources.jar", ".jar");
                    }

                    String project = cleanString(jsonNode.get("project").toString());

                    String previousVersion = cleanString(updatedDependency.get("previousVersion").toString());
                    String newVersion = cleanString(updatedDependency.get("newVersion").toString());
                    String dependencyGroupID = cleanString(updatedDependency.get("dependencyGroupID").toString());
                    String dependencyArtifactID = cleanString(updatedDependency.get("dependencyArtifactID").toString());
                    String preCommitReproductionCommand = cleanString(jsonNode.get("preCommitReproductionCommand").toString());
                    String breakingUpdateReproductionCommand = cleanString(jsonNode.get("breakingUpdateReproductionCommand").toString());
                    String updatedFileType = cleanString(updatedDependency.get("updatedFileType").toString());
                    if (jsonNode.get("failureCategory") != null) {
                        String failureCategory = cleanString(jsonNode.get("failureCategory").toString());
                        System.out.println(project+" Failure category: " + failureCategory);
                        if (failureCategory != null && !(failureCategory.equals("COMPILATION_FAILURE") || failureCategory.equals("WERROR_FAILURE"))) {
                            System.out.println(project + " is of failure type "+failureCategory+" (it therefore gets skipped)!");
                            return;
                        }
                    }

                    if (updatedFileType != null && !updatedFileType.equals("JAR")) {
                        System.out.println(project + " is not JAR related!");
                        return;
                    }



                   /* if(totalPairs.get() > 3){
                        return;
                    }*/

                    // cd5bb39f43e4570b875027073da3d4e43349ead1.json requires plexus-xml in new version => pom edit needed
                    // cb541fd65c7b9bbc3424ea927f1dab223261d156.json has its upgraded dependency deprecated (no classes or code at all in the dependency, just a deprecation notice)
                    // 4a3efad6e00824e5814b9c8f571c9c98aad40281.json has deleted its enum (CertificationPermission) with nothing there to replace it
                    // ghcr.io/chains-project/breaking-updates:17f2bcaaba4805b218743f575919360c5aec5da4-pre straight up fails because it cannot find a dependency
                    // 10d7545c5771b03dd9f6122bd5973a759eb2cd03 cannot be fixed because the dropwizard-core library needs to be upgraded
                    //TODO: Filter BUMP projects so only fixable projects remain (the above two examples are considered not fixable)

                    //TODO: Method chain analysis sometimes returns null, for example: d38182a8a0fe1ec039aed97e103864fce717a0be
                    //TODO also, class name sometimes is * again... (probably due to Method chain analysis)

                    //TODO: Check this: 0abf7148300f40a1da0538ab060552bca4a2f1d8

                    /*

                    2b4d49d68112941b8abb818549389709d8327963
japicmp.exception.JApiCmpException: Failed to load archive from file: zip END header not found
	at japicmp.cmp.JarArchiveComparator.toCtClassStream(JarArchiveComparator.java:229)
	at japicmp.cmp.JarArchiveComparator.lambda$createListOfCtClasses$0(JarArchiveComparator.java:215)
	at java.base/java.util.stream.ReferencePipeline$7$1FlatMap.accept(ReferencePipeline.java:289)
	at java.base/java.util.Collections$2.tryAdvance(Collections.java:5075)
	at java.base/java.util.Collections$2.forEachRemaining(Collections.java:5083)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:921)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.collect(ReferencePipeline.java:727)
	at japicmp.cmp.JarArchiveComparator.createListOfCtClasses(JarArchiveComparator.java:216)
	at japicmp.cmp.JarArchiveComparator.createAndCompareClassLists(JarArchiveComparator.java:179)
	at japicmp.cmp.JarArchiveComparator.compare(JarArchiveComparator.java:90)
	at japicmp.cmp.JarArchiveComparator.compare(JarArchiveComparator.java:78)
	at core.JarDiffUtil.compareJars(JarDiffUtil.java:142)
	at core.JarDiffUtil.<init>(JarDiffUtil.java:71)
	at core.JarDiffUtil.getLazyLoadedInstance(JarDiffUtil.java:61)
	at core.JarDiffUtil.getInstance(JarDiffUtil.java:36)
	at type.TypeProvider.getConflictTypes(TypeProvider.java:39)
	at core.BumpRunner.fixError(BumpRunner.java:597)
	at core.BumpRunner.lambda$runBUMP$1(BumpRunner.java:318)
	at java.base/java.lang.VirtualThread.run(VirtualThread.java:466)
Caused by: java.util.zip.ZipException: zip END header not found
	at java.base/java.util.zip.ZipFile$Source.findEND(ZipFile.java:1648)
	at java.base/java.util.zip.ZipFile$Source.initCEN(ZipFile.java:1656)
	at java.base/java.util.zip.ZipFile$Source.<init>(ZipFile.java:1497)
	at java.base/java.util.zip.ZipFile$Source.get(ZipFile.java:1460)
	at java.base/java.util.zip.ZipFile$CleanableResource.<init>(ZipFile.java:671)
	at java.base/java.util.zip.ZipFile.<init>(ZipFile.java:201)
	at java.base/java.util.zip.ZipFile.<init>(ZipFile.java:148)
	at java.base/java.util.jar.JarFile.<init>(JarFile.java:333)
                     */

                    //TODO: 4aab2869639226035c999c282f31efba15648ea3 className is null
                    /*if (!file.getName().equals("10d7545c5771b03dd9f6122bd5973a759eb2cd03.json")) {
                        return;
                    }*/

                    /*if (!file.getName().equals("fe15d2a6e52b049f6c9e7cc123a5402047a1f01a.json")) {
                        return;
                    }*/

                    String strippedFileName = file.getName().substring(0, file.getName().lastIndexOf("."));


                    String brokenUpdateImage = breakingUpdateReproductionCommand.substring(breakingUpdateReproductionCommand.lastIndexOf(" ")).trim();
                    String oldUpdateImage = preCommitReproductionCommand.substring(preCommitReproductionCommand.lastIndexOf(" ")).trim();

                    String combinedArtifactNameNew = dependencyArtifactID + "-" + newVersion + ".jar";
                    String combinedArtifactNameOld = dependencyArtifactID + "-" + previousVersion + ".jar";

                    System.out.println(file.getName());
                    Path targetPathOld = ContainerUtil.downloadLibrary(mavenSourceLinkPre, outputDir, dockerClient, oldUpdateImage, combinedArtifactNameOld, 0);
                    Path targetPathNew = ContainerUtil.downloadLibrary(mavenSourceLinkBreaking, outputDir, dockerClient, brokenUpdateImage, combinedArtifactNameNew, 0);


                    totalPairs.getAndIncrement();
                    if (Files.exists(targetPathOld) && Files.exists(targetPathNew)) {
                        satisfiedConflictPairs.getAndIncrement();
                    } else {
                        return;
                    }

                    if (!Files.exists(ContainerUtil.getPath(outputDirSrcFiles.getPath(), dependencyArtifactID, strippedFileName))) {
                        CreateContainerResponse oldContainer = ContainerUtil.pullImageAndCreateContainer(dockerClient, oldUpdateImage, false);
                        if (ContainerUtil.logFromContainerContainsError(dockerClient, oldContainer, Path.of(directoryOldContainerLogs + "/" + strippedFileName + "_" + project))) {
                            System.out.println(strippedFileName + "_" + project + " is not working despite being in the pre set!!!!");
                            imposterProjects.incrementAndGet();
                            return;
                        }
                        ContainerUtil.extractDependenciesAndSourceCodeFromContainer(outputDirSrcFiles, dockerClient, oldUpdateImage, dependencyArtifactID + "_" + strippedFileName);
                    }


                    if (!Files.exists(Path.of(bumpConfig.getPathToOutput() + "/brokenLogs/" + strippedFileName + "_" + project))) {
                        ContainerUtil.getBrokenLogFromContainer(dockerClient, brokenUpdateImage, project, strippedFileName, bumpConfig.getPathToOutput());
                    }

                    boolean projectIsFixableWithSourceCodeModification = projectIsFixableThroughCodeModification(Path.of(bumpConfig.getPathToOutput() + "/brokenLogs" + "/" + strippedFileName + "_" + project));

                    if (!projectIsFixableWithSourceCodeModification) {
                        return;
                    }
                    fixableProjects.incrementAndGet();

                    String oldName = targetPathOld.toUri().toString();
                    oldName = oldName.substring(oldName.lastIndexOf("/") + 1);

                    String newName = targetPathNew.toUri().toString();
                    newName = newName.substring(newName.lastIndexOf("/") + 1);

                    Path oldDependencyPath = Path.of(outputDirSrcFiles + "/" + dependencyArtifactID + "_" + strippedFileName + "/tmp/dependencies/" + oldName);

                    if (Files.exists(oldDependencyPath)) {
                        File updatedDependencyFile = new File(targetPathNew.toUri());
                        File oldDependencyFile = new File(oldDependencyPath.toUri());

                        Files.move(oldDependencyFile.toPath(), Path.of(outputDirSrcFiles + "/" + dependencyArtifactID + "_" + strippedFileName + "/tmp/" + oldName), StandardCopyOption.REPLACE_EXISTING);
                        Files.copy(updatedDependencyFile.toPath(), Path.of(outputDirSrcFiles + "/" + dependencyArtifactID + "_" + strippedFileName + "/tmp/dependencies/" + newName), StandardCopyOption.REPLACE_EXISTING);
                    }


                    boolean buildErrorsWereFixed = false;
                    boolean allErrorsWereFixed = false;
                    int amountOfIterations = 0;
                    int amountOfRetries = 0;
                    int retryAtBuildFix = -1;
                    int iterationAtBuildFix = -1;
                    outerloop:
                    for (; amountOfRetries <= bumpConfig.getMaxRetries(); amountOfRetries++) {
                        if(solverCallsPerIteration.containsKey(strippedFileName)) {
                            solverCallsPerIteration.get(strippedFileName).clear();
                        }
                        if(providerCallsPerIteration.containsKey(strippedFileName)) {
                            providerCallsPerIteration.get(strippedFileName).clear();
                        }
                        List<ProposedChange> proposedChanges = new ArrayList<>();
                        HashMap<String, ProposedChange> errorSet = new HashMap<>();
                        Context context = new Context(project, previousVersion, newVersion, dependencyArtifactID, strippedFileName, outputDirClasses, brokenUpdateImage,
                                targetPathOld, targetPathNew, targetDirectoryClasses, outputDirSrcFiles, activeProvider, dockerClient, errorSet, proposedChanges, null,
                                targetDirectoryLLMResponses, targetDirectoryPrompts, targetDirectoryFixedClasses, targetDirectoryFixedLogs, null,
                                bumpConfig, wordSimilarityModel, targetDirectoryResult, llmRequests);

                        context.setRetry(amountOfRetries);

                        List<Object> errors = parseLog(Path.of(bumpConfig.getPathToOutput() + "/brokenLogs" + "/" + strippedFileName + "_" + project));

                        reduceErrors(errors, context);

                        int initialSize = errors.size();

                        System.out.println(project + " contains " + errors.size() + " errors.");

                        List<ErrorLocationProvider> errorLocationProviders = ErrorLocationProvider.getContextProviders(context);
                        List<CodeConflictSolver> codeConflictSolvers = CodeConflictSolver.getCodeConflictSolvers(context);


                        // boolean wasImportRelated = false;
                        for (amountOfIterations = 0; amountOfIterations <= bumpConfig.getMaxIterations(); amountOfIterations++) {
                            long iterationStart = System.currentTimeMillis();
                            try {
                                for (int j = 0; j < errors.size(); j++) {
                                    Object error = errors.get(j);
                                    if (!(error instanceof LogParser.CompileError)) {
                                        continue;
                                    }
                                    solverCallsPerIteration.putIfAbsent(strippedFileName, new ArrayList<>());
                                    providerCallsPerIteration.putIfAbsent(strippedFileName, new ArrayList<>());

                                    context.setCompileError((LogParser.CompileError) error);
                                    context.setStrippedClassName(ContainerUtil.extractClassIfNotCached(context));

                                    fixError(context, errorLocationProviders, codeConflictSolvers,
                                            solverCallsPerIteration.get(strippedFileName), providerCallsPerIteration.get(strippedFileName));

                                }

                                updateIterationStatistic(durationPerIteration, amountOfIterations, strippedFileName, System.currentTimeMillis() - iterationStart);

                                if (validateFix(context, true)) {
                                    if (validateFix(context, false)) {
                                        allErrorsWereFixed = true;
                                        safeResult(context);
                                        break outerloop;
                                    }
                                    if(!buildErrorsWereFixed) {
                                        buildErrorsWereFixed = true;
                                        retryAtBuildFix = amountOfRetries;
                                        iterationAtBuildFix = amountOfIterations;
                                        safeResult(context);
                                        System.out.println("Fixed build of " + strippedFileName + " (Retries: " + amountOfRetries + ", Iteration: " + amountOfIterations + ", buildOnly: " + buildErrorsWereFixed + ")");
                                        System.out.println("Repair continues to fix test execution");
                                    }

                                } else if (amountOfIterations != bumpConfig.getMaxIterations()) {
                                    context.setIteration(context.getIteration() + 1);
                                    context.setTargetDirectoryClasses(targetDirectoryFixedClasses);
                                    //System.out.println(context.getProposedChanges());
                                    createIterationFolders(bumpConfig.getPathToOutput(), context.getIteration());

                                    System.out.println("Project " + file.getName() + " still contains errors.");
                                    //bumpConfig.getPathToOutput() + "/correctedLogs" + "/" + strippedFileName + "_" + project

                                    //E:\master\DependencyConflictResolver\testFiles\correctedLogs\iteration_0
                                    List<Object> newErrors = parseLog(Path.of(bumpConfig.getPathToOutput() + "/correctedLogs" + "/iteration_" + context.getPreviousIteration() + "/" + strippedFileName + "_" + project));

                                    reduceErrors(newErrors, context);

                                    if (!errorsHaveChanged(errors, newErrors)) {
                                        System.out.println("Stopped iteration due to unchanged errors.");
                                        updateRetryStatistic(retryStartedAtIteration, strippedFileName, context.getIteration());
                                        continue outerloop;
                                    }

                                    errors = newErrors;

                                    System.out.println(project + " contains " + errors.size() + " errors (previous iteration had " + initialSize + " errors)");
                                    initialSize = errors.size();
                                    // context.getTargetDirectoryClasses(),

                                }
                            } catch (Exception e) {
                                System.err.println(context.getStrippedFileName());
                                e.printStackTrace();
                            } //finally {
                            //context.getErrorSet().clear();
                            //context.getProposedChanges().clear();
                            //}

                        }
                    }

                    JarDiffUtil.removeCachedJarDiffsForThread();
                    SourceCodeAnalyzer.removeCachedAnalysersForThread();

                    long timeEnd = System.currentTimeMillis();
                    long diff = timeEnd - timeStart;
                    System.out.println("Took " + diff + " ms to process " + project);

                    if (allErrorsWereFixed || buildErrorsWereFixed) {
                        if(buildErrorsWereFixed) {
                            System.out.println("Project "+strippedFileName+" could only be build fixed, reverting retry and iteration statistics to build fix time");
                            amountOfRetries = retryAtBuildFix;
                            amountOfIterations = iterationAtBuildFix;
                        }
                        System.out.println("Fixed " + strippedFileName + " (Retries: " + amountOfRetries + ", Iteration: " + amountOfIterations + ", buildOnly: " + buildErrorsWereFixed + ")");
                        successfulProjectIds.add(strippedFileName);
                        successfulDurations.add((double) diff);
                        successfulIterations.add((double) amountOfIterations);
                        successfulRetries.add((double) amountOfRetries);
                        successfulFixes.getAndIncrement();
                    } else {
                        System.out.println("Could not fix " + strippedFileName);
                        failedFixes.getAndIncrement();
                    }


                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    activeThreadCount.decrementAndGet();
                }
            });
            activeThreads.add(virtualThread);
        }

        for (Thread activeThread : activeThreads) {
            try {
                activeThread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        long globalEndTime = System.currentTimeMillis();
        System.out.println("------------ Summary ------------");
        System.out.println("Took " + (globalEndTime - globalStartTime) + " ms to process all projects");
        System.out.println(imposterProjects.get() + " projects are not buildable despite being in the pre set!!!");
        System.out.println(satisfiedConflictPairs.get() + " out of " + totalPairs.get() + " project pairs have accessible dependencies");
        System.out.println(fixableProjects.get() + " projects are fixable");
        System.out.println("Fixed " + successfulFixes.get() + " out of " + satisfiedConflictPairs.get() + " projects (" + failedFixes.get() + " of the fixable projects were not fixed)");
        System.out.println("Number of llm requests: " + llmRequests.get());
        for (ConflictType conflictType : conflicts.keySet()) {
            System.out.println("Detected " + conflicts.get(conflictType).get() + " " + conflictType + " conflicts");
        }

        for (String solverName : errorsAssignedToSolvers.keySet()) {
            System.out.println("Assigned " + errorsAssignedToSolvers.get(solverName).get() + " errors to " + solverName + "");
        }

        List<Double> aggregatedDurationsPerIterationOfAllProjects = new ArrayList<>(bumpConfig.getMaxIterations());
        List<Integer> aggregatedIterationCountOfAllProjects = new ArrayList<>(bumpConfig.getMaxIterations());

        List<Double> aggregatedDurationsPerIterationOfSuccessfulProjects = new ArrayList<>(bumpConfig.getMaxIterations());
        List<Integer> aggregatedIterationCountOfSuccessfulProjects = new ArrayList<>(bumpConfig.getMaxIterations());

        List<Double> aggregatedDurationsPerIterationOfUnSuccessfulProjects = new ArrayList<>(bumpConfig.getMaxIterations());
        List<Integer> aggregatedIterationCountOfUnSuccessfulProjects = new ArrayList<>(bumpConfig.getMaxIterations());


        for (int i = 0; i <= bumpConfig.getMaxIterations(); i++) {
            aggregatedDurationsPerIterationOfSuccessfulProjects.add(0.0);
            aggregatedIterationCountOfSuccessfulProjects.add(0);
            aggregatedDurationsPerIterationOfUnSuccessfulProjects.add(0.0);
            aggregatedIterationCountOfUnSuccessfulProjects.add(0);
            aggregatedDurationsPerIterationOfAllProjects.add(0.0);
            aggregatedIterationCountOfAllProjects.add(0);
        }

        System.out.println("Durations per iteration (of the last retry) of successful Projects: ");
        for (String successfulProjectId : successfulProjectIds) {
            processDurationStatistic(durationPerIteration, aggregatedDurationsPerIterationOfSuccessfulProjects, aggregatedIterationCountOfSuccessfulProjects, successfulProjectId);
        }

        System.out.println();

        System.out.println("Durations per iteration (of the last retry) of unsuccessful Projects: ");
        for (String projectId : durationPerIteration.keySet()) {
            if (!successfulProjectIds.contains(projectId)) {
                processDurationStatistic(durationPerIteration, aggregatedDurationsPerIterationOfUnSuccessfulProjects, aggregatedIterationCountOfUnSuccessfulProjects, projectId);
            }
        }

        System.out.println();

        System.out.println("Durations per iteration (of the last retry) of all Projects: ");
        for (String projectId : durationPerIteration.keySet()) {
            processDurationStatistic(durationPerIteration, aggregatedDurationsPerIterationOfAllProjects, aggregatedIterationCountOfAllProjects, projectId);

        }
        System.out.println();

        System.out.println("Duration statistics of successful fixes: ");
        printStatistics(successfulDurations);
        System.out.println();
        System.out.println("Iteration statistics of successful fixes: ");
        printStatistics(successfulIterations);
        System.out.println();
        System.out.println("Retry statistics of successful fixes: ");
        printStatistics(successfulRetries);
        System.out.println();
        System.out.println("Aggregated iteration durations of successful Projects: ");
        for (int i = 0; i < aggregatedDurationsPerIterationOfSuccessfulProjects.size(); i++) {
            System.out.println("Iteration " + i + ": Sum: " + aggregatedDurationsPerIterationOfSuccessfulProjects.get(i) + "ms (count: " + aggregatedIterationCountOfSuccessfulProjects.get(i) + ")");
        }

        System.out.println();

        System.out.println("Aggregated iteration durations of unsuccessful Projects: ");
        for (int i = 0; i < aggregatedDurationsPerIterationOfUnSuccessfulProjects.size(); i++) {
            System.out.println("Iteration " + i + ": " + aggregatedDurationsPerIterationOfUnSuccessfulProjects.get(i) + "ms (count: " + aggregatedIterationCountOfUnSuccessfulProjects.get(i) + ")");
        }

        System.out.println();

        System.out.println("Aggregated iteration durations of all Projects: ");
        for (int i = 0; i < aggregatedDurationsPerIterationOfUnSuccessfulProjects.size(); i++) {
            System.out.println("Iteration " + i + ": " + aggregatedDurationsPerIterationOfAllProjects.get(i) + "ms (count: " + aggregatedIterationCountOfAllProjects.get(i) + ")");
        }

        System.out.println();

        List<List<Double>> durationsPerIterationOfNonSuccessfulProjects = new ArrayList<>();
        List<List<Double>> durationsPerIterationOfSuccessfulProjects = new ArrayList<>();
        List<List<Double>> durationsPerIterationOfAllProjects = new ArrayList<>();

        durationsPerIterationOfNonSuccessfulProjects.add(new ArrayList<>());
        for (String projectId : durationPerIteration.keySet()) {
            for (int i = 0; i < durationPerIteration.get(projectId).size(); i++) {
                if (!successfulProjectIds.contains(projectId)) {
                    if(durationsPerIterationOfNonSuccessfulProjects.size()<=i){
                        durationsPerIterationOfNonSuccessfulProjects.add(new ArrayList<>());
                    }
                    durationsPerIterationOfNonSuccessfulProjects.get(i).add((double)durationPerIteration.get(projectId).get(i));
                }else{
                    if(durationsPerIterationOfSuccessfulProjects.size()<=i){
                        durationsPerIterationOfSuccessfulProjects.add(new ArrayList<>());
                    }
                    durationsPerIterationOfSuccessfulProjects.get(i).add((double)durationPerIteration.get(projectId).get(i));
                }
                if(durationsPerIterationOfAllProjects.size()<=i){
                    durationsPerIterationOfAllProjects.add(new ArrayList<>());
                }
                durationsPerIterationOfAllProjects.get(i).add((double)durationPerIteration.get(projectId).get(i));
            }

        }
        System.out.println("Statistics of the duration of successful iterations: ");
        for (int i = 0; i < durationsPerIterationOfSuccessfulProjects.size(); i++) {
            System.out.println("Iteration "+i+": ");
            printStatistics(durationsPerIterationOfSuccessfulProjects.get(i));
            System.out.println();
        }

        System.out.println();


        System.out.println("Statistics of the duration of non-successful iterations: ");
        for (int i = 0; i < durationsPerIterationOfNonSuccessfulProjects.size(); i++) {
            System.out.println("Iteration "+i+": ");
            printStatistics(durationsPerIterationOfNonSuccessfulProjects.get(i));
            System.out.println();
        }

        System.out.println();

        System.out.println("Statistics of the duration of all iterations: ");
        for (int i = 0; i < durationsPerIterationOfAllProjects.size(); i++) {
            System.out.println("Iteration "+i+": ");
            printStatistics(durationsPerIterationOfAllProjects.get(i));
            System.out.println();
        }

        System.out.println();

        System.out.println("Provider calls of all Projects: ");
        printSolverOrProviderStatistics(providerCallsPerIteration);

        System.out.println();

        System.out.println("Solver calls of all Projects: ");
        printSolverOrProviderStatistics(solverCallsPerIteration);
      }

    private static void printSolverOrProviderStatistics(ConcurrentHashMap<String, List<HashMap<String, CallDetails>>> data) {
        for (String projectId : data.keySet()) {
            List<HashMap<String, CallDetails>> solverCallsPerIterationOfProject = data.get(projectId);
            System.out.println(projectId + ": ");
            printSolverOrProviderStatisticsOfProject(solverCallsPerIterationOfProject);
        }
    }

    private static void printSolverOrProviderStatisticsOfProject(List<HashMap<String, CallDetails>> data) {
        for (int i = 0; i < data.size(); i++) {
            System.out.println("  Iteration " + i + ": ");
            for (String name : data.get(i).keySet()) {
                System.out.println("    " + name + ": " + data.get(i).get(name).getAmount());
                for(String callDetails : data.get(i).get(name).getDetails()){
                    System.out.println("      " + callDetails);
                }
            }
        }
    }

    private static List<HashMap<String, Integer>> getAggregatedSolverOrProviderStatistics(ConcurrentHashMap<String, List<HashMap<String, Integer>>> data, List<String> successfulProjectIds, int maxIterations) {
        List<HashMap<String, Integer>> aggregatedCallsOfSuccessfulProjects = new ArrayList<>();
        for (int i = 0; i < maxIterations; i++) {
            aggregatedCallsOfSuccessfulProjects.add(new HashMap<>());
        }
        for (String projectId : data.keySet()) {
            if (!successfulProjectIds.contains(projectId)) {
                continue;
            }
            List<HashMap<String, Integer>> callsPerIterationOfProject = data.get(projectId);
            for (int i = 0; i < callsPerIterationOfProject.size(); i++) {
                for (String name : callsPerIterationOfProject.get(i).keySet()) {
                    //System.out.println("    " + name + ": " + callsPerIterationOfProject.get(i).get(name));
                    int existingCount = 0;
                    if (aggregatedCallsOfSuccessfulProjects.get(i).containsKey(name)) {
                        existingCount = aggregatedCallsOfSuccessfulProjects.get(i).get(name);
                    }
                    aggregatedCallsOfSuccessfulProjects.get(i).put(name, existingCount + callsPerIterationOfProject.get(i).get(name));
                }
            }
        }
        return aggregatedCallsOfSuccessfulProjects;
    }

    private static void processDurationStatistic(ConcurrentHashMap<String, List<Long>> durationPerIteration, List<Double> aggregatedDurationsPerIterationOfSuccessfulProjects, List<Integer> aggregatedIterationCountOfSuccessfulProjects, String projectId) {
        System.out.println(projectId + ": " + durationPerIteration.get(projectId));
        for (int i = 0; i < durationPerIteration.get(projectId).size(); i++) {
            double castDuration = (double) durationPerIteration.get(projectId).get(i);
            aggregatedDurationsPerIterationOfSuccessfulProjects.set(i, aggregatedDurationsPerIterationOfSuccessfulProjects.get(i) + castDuration);
            aggregatedIterationCountOfSuccessfulProjects.set(i, aggregatedIterationCountOfSuccessfulProjects.get(i) + 1);
        }
    }

    public static void printStatistics(List<Double> list) {
        DoubleSummaryStatistics stats = list.stream()
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();

        System.out.println("Average: " + stats.getAverage());
        System.out.println("Min: " + stats.getMin());
        System.out.println("Max: " + stats.getMax());
        System.out.println("Count: " + stats.getCount());


        List<Double> sorted = list.stream()
                .sorted()
                .toList();

        double median;
        int size = sorted.size();
        if (size > 1) {
            if (size % 2 == 0) {
                median = (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
            } else {
                median = sorted.get(size / 2);
            }
            System.out.println("Median: " + median);


            double p25 = sorted.get((int) (0.25 * (size - 1)));
            double p75 = sorted.get((int) (0.75 * (size - 1)));
            System.out.println("25th percentile: " + p25);
            System.out.println("75th percentile: " + p75);


            double mean = stats.getAverage();
            double variance = list.stream()
                    .mapToDouble(d -> Math.pow(d - mean, 2))
                    .average()
                    .orElse(Double.NaN);
            double stdDev = Math.sqrt(variance);

            System.out.println("Variance: " + variance);
            System.out.println("Standard Deviation: " + stdDev);
        }
    }

    public static boolean validateFix(Context context, boolean buildOnly) {
        HashMap<String, List<ProposedChange>> groupedChangesByClassName = new HashMap<>();

        for (ProposedChange proposedChange : context.getProposedChanges()) {
            if (!groupedChangesByClassName.containsKey(proposedChange.className())) {
                groupedChangesByClassName.put(proposedChange.className(), new ArrayList<>());
            }
            groupedChangesByClassName.get(proposedChange.className()).add(proposedChange);
        }

        CreateContainerResponse container = ContainerUtil.pullImageAndCreateContainer(context.getDockerClient(), context.getBrokenUpdateImage(), buildOnly);

        List<String> classesToReplace = new ArrayList<>(context.getFixedClassesFromPastIterations().keySet());

        for (String className : groupedChangesByClassName.keySet()) {

            ContainerUtil.replaceBrokenCodeInClass(className, context.getTargetDirectoryClasses(), context.getTargetDirectoryFixedClasses(), context.getStrippedFileName(), groupedChangesByClassName.get(className), context.getIteration());

            ContainerUtil.replaceFileInContainer(context.getDockerClient(), container, ContainerUtil.getPathWithIteration(context.getTargetDirectoryFixedClasses(), context.getStrippedFileName(), className, context.getIteration()), groupedChangesByClassName.get(className).get(0).file());

            classesToReplace.remove(className);
        }

        // Classes from past iterations
        for (String className : classesToReplace) {
            System.out.println("Also replaced " + className);
            PathComponents pathComponents = context.getFixedClassesFromPastIterations().get(className);
            ContainerUtil.replaceFileInContainer(context.getDockerClient(), container, pathComponents.path(), pathComponents.fileNameInContainer());
        }

        for (String className : groupedChangesByClassName.keySet()) {
            context.getFixedClassesFromPastIterations().put(className,
                    new PathComponents(ContainerUtil.getPathWithIteration(context.getTargetDirectoryFixedClasses(), context.getStrippedFileName(), className, context.getIteration()), groupedChangesByClassName.get(className).get(0).file()));
        }

        return !ContainerUtil.logFromContainerContainsError(context.getDockerClient(), container, ContainerUtil.getPathWithIteration(context.getTargetDirectoryFixedLogs(), context.getStrippedFileName(), context.getProject(), context.getIteration()));
    }

    public static void safeResult(Context context) {
        File resultFolderForProject = new File(context.getTargetDirectoryResult() + "/" + context.getStrippedFileName() + "_" + context.getProject());
        resultFolderForProject.mkdirs();

        for (String className : context.getFixedClassesFromPastIterations().keySet()) {
            System.out.println("Saving fixed " + className + " to " + resultFolderForProject.toPath().resolve(Path.of(className)));
            try {
                Files.copy(context.getFixedClassesFromPastIterations().get(className).path(), resultFolderForProject.toPath().resolve(Path.of(className)), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void sortErrors(List<Object> errors) {
        errors.sort(new Comparator<Object>() {
            @Override
            public int compare(Object e1, Object e2) {
                if (e1 instanceof LogParser.CompileError && e2 instanceof LogParser.CompileError) {
                    LogParser.CompileError c1 = (LogParser.CompileError) e1;
                    LogParser.CompileError c2 = (LogParser.CompileError) e2;

                    return -Boolean.compare(c1.isImportRelated, c2.isImportRelated);
                }

                return 1;
            }
        });
    }

    public static void reduceErrors(List<Object> errors, Context context) {
        removeDuplicatedLogErrorEntries(errors);
        removeErrorsCausedByImportError(errors, context);
    }

    public static boolean errorsHaveChanged(List<Object> oldErrors, List<Object> newErrors) {
        int sameErrors = 0;
        for (Object oldError : oldErrors) {
            if (oldError instanceof LogParser.CompileError oldCompileError) {
                for (Object newError : newErrors) {
                    if (newError instanceof LogParser.CompileError newCompileError) {
                        if (oldCompileError.message.equals(newCompileError.message) && oldCompileError.line == newCompileError.line) {
                            sameErrors++;
                            break;
                        }
                    }
                }
            }
        }
        return sameErrors < oldErrors.size();
    }

    public static void removeErrorsCausedByImportError(List<Object> errors, Context context) {
        HashMap<String, LogParser.CompileError> importRelatedErrorMap = new HashMap<>();
        for (Object error : errors) {
            if (error instanceof LogParser.CompileError) {
                LogParser.CompileError compileError = (LogParser.CompileError) error;
                context.setCompileError(compileError);
                context.setStrippedClassName(ContainerUtil.extractClassIfNotCached(context));
                BrokenCode brokenCode = ContainerUtil.readBrokenLine(context.getStrippedClassName(), context.getTargetDirectoryClasses(),
                        context.getStrippedFileName(), new int[]{context.getCompileError().line, context.getCompileError().column}, context.getIteration());

                if (compileError.isImportRelated && brokenCode.code().trim().startsWith("import ")) {
                    String className = brokenCode.code().trim();
                    className = className.substring(className.indexOf(" ") + 1, className.indexOf(";"));
                    className = className.substring(className.lastIndexOf(".") + 1);
                    importRelatedErrorMap.put(compileError.file + "/" + className, compileError);
                }
            }
        }

        for (int j = errors.size() - 1; j >= 0; j--) {
            if (errors.get(j) instanceof LogParser.CompileError) {
                LogParser.CompileError compileError = (LogParser.CompileError) errors.get(j);
                if (compileError.message.startsWith("cannot find symbol")) {
                    if (compileError.details.containsKey("symbol")) {
                        String symbolName = compileError.details.get("symbol");
                        symbolName = symbolName.substring(symbolName.indexOf(" ") + 1);
                        if (importRelatedErrorMap.containsKey(compileError.file + "/" + symbolName)) {
                            System.out.println("Removed '" + compileError.message + "(" + compileError.details + ")' because prior errors already handle the import of " + symbolName);
                            errors.remove(j);
                        }
                    }
                }
            }

        }
    }

    public static void removeDuplicatedLogErrorEntries(List<Object> errors) {
        for (int i = errors.size() - 1; i >= 0; i--) {
            LogParser.CompileError compileError = null;
            if (errors.get(i) instanceof LogParser.CompileError) {
                compileError = (LogParser.CompileError) errors.get(i);
            }

            for (int j = i - 1; j >= 0; j--) {
                if (errors.get(j) instanceof LogParser.CompileError && compileError != null) {
                    LogParser.CompileError innerCompileError = (LogParser.CompileError) errors.get(j);
                    if (innerCompileError.file.equals(compileError.file) && innerCompileError.line == compileError.line) {
                        // Keep details
                        ((LogParser.CompileError) errors.get(i)).details.putAll(((LogParser.CompileError) errors.get(j)).details);
                        errors.remove(j);
                        i--;
                        break;
                    }
                }
            }
        }
    }


    public static void fixError(Context context, List<ErrorLocationProvider> errorLocationProviders, List<CodeConflictSolver> codeConflictSolvers,
                                List<HashMap<String, CallDetails>> solverCallsPerIteration, List<HashMap<String, CallDetails>> providerCallsPerIteration) throws IOException, ClassNotFoundException {
        BrokenCode brokenCode = ContainerUtil.readBrokenLine(context.getStrippedClassName(), context.getTargetDirectoryClasses(),
                context.getStrippedFileName(), new int[]{context.getCompileError().line, context.getCompileError().column}, context.getIteration());


        String trimmedBrokenCode = brokenCode.code().trim();
        Matcher nonReducableErrorMatcher = Pattern.compile("^\\s*[}{)( ;]+\\s*$").matcher(trimmedBrokenCode);

        if (nonReducableErrorMatcher.find()) {
            System.out.println(trimmedBrokenCode + " is not reducible");
        } else {
            if (context.getErrorSet().containsKey(trimmedBrokenCode)) {
                context.getProposedChanges().add(new ProposedChange(context.getStrippedClassName(), context.getErrorSet().get(trimmedBrokenCode).code(), context.getCompileError().file,
                        brokenCode.start(), brokenCode.end()));
                System.out.println("Similar error in proposed changes. Changed " + trimmedBrokenCode + " to " + context.getErrorSet().get(trimmedBrokenCode).code() + ". Added past fix with position adjustment");
                return;
            }
        }

        System.out.println(context.getCompileError().file + " " + context.getCompileError().line + " " + context.getCompileError().column);
        ErrorLocation errorLocation = new ErrorLocation("", "", new String[0]);

        boolean errorGetsTargetByAtLeastOneProvider = false;
        for (ErrorLocationProvider errorLocationProvider : errorLocationProviders) {
            if (errorLocationProvider.errorIsTargetedByProvider(context.getCompileError(), brokenCode)) {
                errorGetsTargetByAtLeastOneProvider = true;

                errorLocation = errorLocationProvider.getErrorLocation(context.getCompileError(), brokenCode);
                updateProviders(errorLocationProvider.getClass().getName(), context.getIteration(), providerCallsPerIteration, errorLocation.toString());
                break;
            }
        }

        if (!errorGetsTargetByAtLeastOneProvider) {
            System.out.println("UNCATEGORIZED " + brokenCode.code());
        }

        List<ConflictType> conflictTypes = TypeProvider.getConflictTypes(brokenCode, errorLocation, context.getCompileError(), context);
        for (ConflictType conflictType : conflictTypes) {
            System.out.println("Conflict type " + conflictType);
        }

        updateConflicts(conflictTypes);

        for (CodeConflictSolver codeConflictSolver : codeConflictSolvers) {
            if (codeConflictSolver.errorIsTargetedBySolver(context.getCompileError(), brokenCode, errorLocation, conflictTypes)) {
                ProposedChange proposedChange = codeConflictSolver.solveConflict(context.getCompileError(), brokenCode, errorLocation);
                if (proposedChange != null) {
                    System.out.println(codeConflictSolver.getClass().getName() + " proposed " + proposedChange.code());
                    updateSolvers(codeConflictSolver.getClass().getName(), context.getIteration(), solverCallsPerIteration, codeConflictSolver.getClass().getName() +" changed "+brokenCode.code().trim()+" to "+proposedChange.code());
                    context.getProposedChanges().add(proposedChange);
                    context.getErrorSet().put(brokenCode.code().trim(), proposedChange);
                    return;
                }
            }
        }

        System.out.println("Target class: " + errorLocation.className());
        System.out.println("Target method: " + errorLocation.methodName());
    }

    private static String cleanString(String str) {
        if (str.equals("null")) {
            return null;
        }
        return str.substring(1, str.length() - 1);
    }

    public static void updateConflicts(List<ConflictType> types) {
        for (ConflictType conflictType : types) {
            conflicts.putIfAbsent(conflictType, new AtomicInteger());

            conflicts.get(conflictType).incrementAndGet();
        }
    }

    public static void updateSolvers(String solverName, int iteration, List<HashMap<String, CallDetails>> solverCallsPerIteration, String detail) {
        errorsAssignedToSolvers.putIfAbsent(solverName, new AtomicInteger());
        errorsAssignedToSolvers.get(solverName).incrementAndGet();

        updateProviders(solverName, iteration, solverCallsPerIteration, detail);
    }


    public static void updateProviders(String providerName, int iteration, List<HashMap<String, CallDetails>> providerCallsPerIteration, String detail) {
        while (providerCallsPerIteration.size() <= iteration) {
            providerCallsPerIteration.add(new HashMap<>());
        }
        if (providerCallsPerIteration.get(iteration).putIfAbsent(providerName, new CallDetails(1, new ArrayList<>(Arrays.asList(detail)))) != null) {
            CallDetails details = providerCallsPerIteration.get(iteration).get(providerName);
            details.getDetails().add(detail);
            details.incrementAmount();
        }
    }

    public static void updateIterationStatistic(ConcurrentHashMap<String, List<Long>> durationPerIteration, int iteration, String id, long duration) {
        if(iteration == 0){
            durationPerIteration.put(id, new ArrayList<>());
        }
        durationPerIteration.get(id).add(duration);
    }

    public static void updateRetryStatistic(ConcurrentHashMap<String, List<Integer>> retryAtIteration, String id, int iteration) {
        retryAtIteration.putIfAbsent(id, new ArrayList<>());
        retryAtIteration.get(id).add(iteration);
    }


}
