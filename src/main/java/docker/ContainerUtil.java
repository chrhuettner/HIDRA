package docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PullResponseItem;
import dto.BrokenCode;
import context.Context;
import dto.ClassPath;
import dto.ProposedChange;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class ContainerUtil {


    public static String readParent(String className, String directory, String fileName, int iteration) {
        String regex = ".*class .* extends .*";
        try {
            BufferedReader br = new BufferedReader(new FileReader(getPathWithRespectToIteration(directory, fileName, className, iteration, true).toFile()));

            String line = null;
            int lineNumber = 1;
            while ((line = br.readLine()) != null) {
                if (line.matches(regex)) {
                    return line.substring(line.indexOf("extends ") + "extends ".length(), line.indexOf("{")).trim();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public static List<String> getAllExtensionsAndImplementations(Path path) {
        String regex = ".*class .* extends .*";
        List<String> classNames = new ArrayList<>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(path.toFile()));

            String line = null;
            int lineNumber = 1;
            while ((line = br.readLine()) != null) {
                if (line.matches(regex)) {
                    classNames.add(line.substring(line.indexOf("extends ") + "extends ".length(), line.indexOf("{")).trim());
                    int implementsIndex = line.indexOf("implements ");
                    if (implementsIndex != -1) {
                        String interfaces = line.substring(implementsIndex + "implements ".length(), line.indexOf("{")).trim();
                        String[] interfacesArray = interfaces.split(",");
                        for (String interfaceName : interfacesArray) {
                            classNames.add(interfaceName);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return classNames;
    }

    public static Path getPathSuffixWithRespectToIteration(String fileName, String className, int iteration, boolean usePreviousIteration) {
        if (iteration == 0) {
            return Path.of(fileName + "_" + className);
        }
        if (usePreviousIteration) {
            iteration--;
        }
        return Path.of("iteration_" + iteration + "/" + fileName + "_" + className);
    }


    public static Path getPathWithIteration(String directory, String fileName, String className, int iteration) {
        return Path.of(directory + "/iteration_" + iteration + "/" + fileName + "_" + className);
    }

    public static Path getPath(String directory, String fileName, String className) {
        return Path.of(directory + "/" + fileName + "_" + className);
    }

    public static Path getPathWithRespectToIteration(String directory, String fileName, String className, int iteration, boolean usePreviousIteration) {
        if (iteration == 0) {
            return getPath(directory, fileName, className);
        }
        if (usePreviousIteration) {
            iteration--;
        }
        return getPathWithIteration(directory, fileName, className, iteration);
    }

    public static void replaceBrokenCodeInClass(String className, String directory, String outDirectory, String fileName, List<ProposedChange> changes, int iteration) {
        try {
            List<String> lines = Files.readAllLines(getPathWithRespectToIteration(directory, fileName, className, iteration, true));

            for (ProposedChange change : changes) {
                lines.set(change.start() - 1, change.code());
                for (int i = change.start(); i < change.end(); i++) {
                    lines.set(i, "");
                }
            }

            // Use \n so the docker containers (linux) don't complain
            String content = String.join("\n", lines) + "\n";

            File file = new File(getPathWithIteration(outDirectory, fileName, className, iteration).toUri());

            try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
                writer.write(content);
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static ClassPath getPathToBrokenClass(Context context) {
        String strippedClassName = context.getCompileError().file.substring(context.getCompileError().file.lastIndexOf("/") + 1);
        if (context.getIteration() == 0) {
            return new ClassPath(Path.of(context.getConfig().getPathToOutput() + "/brokenClasses/" + context.getStrippedFileName() + "_" + strippedClassName), strippedClassName);
        } else {
            return new ClassPath(Path.of(context.getConfig().getPathToOutput() + "/correctedClasses/iteration_" + (context.getIteration() - 1) + "/" + context.getStrippedFileName() + "_" + strippedClassName), strippedClassName);
        }
    }

    public static String extractClassIfNotCached(Context context) {
        ClassPath classPath = getPathToBrokenClass(context);
        //if (context.getIteration() == 0) {
        if (!Files.exists(classPath.path())) {
            File classFileFolder;
            if(context.getIteration() == 0){
                classFileFolder = context.getOutputDirClasses();
            }else{
                classFileFolder = new File(context.getTargetDirectoryFixedClasses());
            }
            extractClassFromContainer(classFileFolder, context.getDockerClient(), context.getBrokenUpdateImage(), context.getCompileError().file, context.getStrippedFileName(), context.getIteration());
        } else {
            System.out.println("Class already exists at " + classPath);
        }
        //}
        return classPath.strippedClassName();
    }

    public static void extractEntry(TarArchiveInputStream tais, TarArchiveEntry entry, File outputDir, String outputNameModifier, int iteration) throws IOException {
        File outputFile;
        if (iteration == 0) {
            outputFile = new File(outputDir, outputNameModifier + entry.getName().substring(entry.getName().lastIndexOf('/') + 1));
        }else{
            outputFile = new File(outputDir, "iteration_"+(iteration-1)+"/"+outputNameModifier + entry.getName().substring(entry.getName().lastIndexOf('/') + 1));
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[8192];
            int len;
            long remaining = entry.getSize();
            while (remaining > 0 && (len = tais.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                fos.write(buffer, 0, len);
                remaining -= len;
            }
        }
    }

    public static void extractWholeEntry(TarArchiveInputStream tais, TarArchiveEntry entry, File outputDir) throws IOException {
        File outputFile = new File(outputDir, entry.getName());

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[8192];
            int len;
            long remaining = entry.getSize();
            while (remaining > 0 && (len = tais.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                fos.write(buffer, 0, len);
                remaining -= len;
            }
        }
    }


    public static int[] getBracketOccurrencesInString(String line) {
        int[] occurrences = new int[2];
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == '(') {
                occurrences[0]++;
            } else if (c == ')') {
                occurrences[1]++;
            }
        }

        return occurrences;
    }

    public static void extractLibraryFromContainer(File targetDirectory, DockerClient dockerClient, String imagePath, String artifactNameWithVersion, int iteration) {
        System.out.println("Fetching library from container (this can take some time)");
        CreateContainerResponse container = pullImageAndCreateContainer(dockerClient, imagePath);
        getFileFromContainer(dockerClient, container, artifactNameWithVersion, targetDirectory, "", iteration);
        dockerClient.removeContainerCmd(container.getId()).exec();
    }

    public static void extractClassFromContainer(File targetDirectory, DockerClient dockerClient, String imagePath, String className, String fileName, int iteration) {
        System.out.println("Fetching class from container (this can take some time)");
        CreateContainerResponse container = pullImageAndCreateContainer(dockerClient, imagePath);
        getFileFromContainer(dockerClient, container, className, targetDirectory, fileName + "_", iteration);
        dockerClient.removeContainerCmd(container.getId()).exec();
    }

    public static void extractDependenciesAndSourceCodeFromContainer(File targetDirectory, DockerClient dockerClient, String imagePath, String projectName) {
        System.out.println("Fetching class from container (this can take some time)");
        CreateContainerResponse container = pullImageAndCreateContainerWithDependenciesCommand(dockerClient, imagePath);

        dockerClient.startContainerCmd(container.getId()).exec();
        try {
            dockerClient.waitContainerCmd(container.getId()).start().awaitCompletion();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        File projectDir = new File(targetDirectory, projectName);

        if (!projectDir.exists()) {
            projectDir.mkdirs();
        }


        getSourceFiles(dockerClient, container, projectDir);
        getDependencyFiles(dockerClient, container, projectDir);
        dockerClient.removeContainerCmd(container.getId()).exec();
    }

    public static void getBrokenLogFromContainer(DockerClient dockerClient, String imagePath, String projectName, String fileName, String basePath) {
        CreateContainerResponse container = pullImageAndCreateContainer(dockerClient, imagePath);

        dockerClient.startContainerCmd(container.getId()).exec();
        dockerClient.waitContainerCmd(container.getId()).start().awaitStatusCode();
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(basePath + "/brokenLogs/" + fileName + "_" + projectName));

            dockerClient.logContainerCmd(container.getId())
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTailAll()
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            String s = new String(frame.getPayload());

                            try {
                                bw.write(s);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }).awaitCompletion();
            bw.flush();
            dockerClient.removeContainerCmd(container.getId()).exec();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean logFromContainerContainsError(DockerClient dockerClient, CreateContainerResponse container, Path path) {
        dockerClient.startContainerCmd(container.getId()).exec();
        dockerClient.waitContainerCmd(container.getId()).start().awaitStatusCode();
        final boolean[] containsError = {false};
        try {
            File file = new File(path.toUri());

            BufferedWriter bw = new BufferedWriter(new FileWriter(file));

            dockerClient.logContainerCmd(container.getId())
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTailAll()
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            String s = new String(frame.getPayload());
                            if (s.contains("[ERROR]")) {
                                containsError[0] = true;
                            }
                            try {
                                bw.write(s);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }).awaitCompletion();
            bw.flush();
            dockerClient.removeContainerCmd(container.getId()).exec();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }

        return containsError[0];
    }

    public static BrokenCode readBrokenLine(String className, String directory, String fileName, int[] indices, int iteration) {
        try {

            /*File file = new File(getPathWithRespectToIteration(directory, fileName, className, iteration, true).toUri());
            if(!file.exists()) {
                ContainerUtil.extractClassIfNotCached(context);
            }*/

            List<String> allLines = Files.readAllLines(getPathWithRespectToIteration(directory, fileName, className, iteration, true));

            //BufferedReader br = new BufferedReader(new FileReader(directory + "/" + fileName + "_" + className));
            String brokenCode = null;
            int start = 0, end = allLines.size();
            for (int i = 0; i < allLines.size(); i++) {
                if (i + 1 == indices[0]) {
                    start = i + 1;
                    brokenCode = allLines.get(i);
                    int[] bracketOccurrencesInLine = getBracketOccurrencesInString(brokenCode);
                    if (!(brokenCode.endsWith(";") || brokenCode.endsWith("{") || brokenCode.endsWith("}") || (bracketOccurrencesInLine[0] == bracketOccurrencesInLine[1] && bracketOccurrencesInLine[0] != 0))) {
                        for (int j = i + 1; j < allLines.size(); j++) {
                            String line = allLines.get(j);

                            brokenCode = brokenCode + line;
                            if (allLines.get(j).endsWith(";")) {
                                end = j + 1;
                                break;
                            }

                            bracketOccurrencesInLine = getBracketOccurrencesInString(brokenCode);

                            if (bracketOccurrencesInLine[0] == bracketOccurrencesInLine[1] && bracketOccurrencesInLine[0] != 0) {
                                end = j + 1;
                                break;
                            }


                        }
                    } else {
                        end = start;
                    }
                    return new BrokenCode(brokenCode, start, end, "");
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public static Path downloadLibrary(String libraryUrl, File targetDirectory, DockerClient dockerClient, String imagePath, String artifactNameWithVersion, int iteration) {
        Path targetPath = Path.of(targetDirectory.getPath()).resolve(artifactNameWithVersion);
        if (!Files.exists(targetPath)) {
            if (libraryUrl == null) {

                extractLibraryFromContainer(targetDirectory, dockerClient, imagePath, artifactNameWithVersion, iteration);

            } else {
                try {
                    URL url = new URI(libraryUrl).toURL();
                    InputStream inPrev = url.openStream();
                    Files.copy(inPrev, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException | URISyntaxException e) {
                    System.err.println("Error downloading " + artifactNameWithVersion + " from " + libraryUrl + ". Resorting to container extraction");

                    extractLibraryFromContainer(targetDirectory, dockerClient, imagePath, artifactNameWithVersion, iteration);

                }
            }
        } else {
            System.out.println("Dependency already cached locally at " + targetPath);
        }
        return targetPath;
    }

    public static CreateContainerResponse pullImageAndCreateContainerWithDependenciesCommand(DockerClient dockerClient, String imagePath) {
        try {
            dockerClient.pullImageCmd(imagePath)
                    .exec(new PullImageResultCallback() {
                        @Override
                        public void onNext(PullResponseItem item) {
                            String status = item.getStatus();
                            String progress = item.getProgress();
                            String id = item.getId();

                            if (status != null) {
                                if (progress != null && !progress.isEmpty()) {
                                    System.out.printf("%s: %s %s%n", id != null ? id : "", status, progress);
                                } else {
                                    System.out.printf("%s: %s%n", id != null ? id : "", status);
                                }
                            }

                            super.onNext(item);
                        }
                    }).awaitCompletion();
            //TODO: Check if this works for all BUMP projects (it probably wont lol)
            return dockerClient.createContainerCmd(imagePath)
                    .withCmd("sh", "-c",
                            //"mvn clean test -B | tee %s.log")
                            // Use multiple lines instead of comma seperated scopes because of older maven versions
                            "mvn -B dependency:copy-dependencies -DoutputDirectory=/tmp/dependencies")
                    //"mvn clean package org.apache.maven.plugins:maven-shade-plugin:3.5.0:shade -Dmaven.test.skip=true")
                    .exec();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static CreateContainerResponse pullImageAndCreateContainer(DockerClient dockerClient, String imagePath) {
        try {
            dockerClient.pullImageCmd(imagePath)
                    .exec(new PullImageResultCallback() {
                        @Override
                        public void onNext(PullResponseItem item) {
                            String status = item.getStatus();
                            String progress = item.getProgress();
                            String id = item.getId();

                            if (status != null) {
                                if (progress != null && !progress.isEmpty()) {
                                    System.out.printf("%s: %s %s%n", id != null ? id : "", status, progress);
                                } else {
                                    System.out.printf("%s: %s%n", id != null ? id : "", status);
                                }
                            }

                            super.onNext(item);
                        }
                    }).awaitCompletion();
            return dockerClient.createContainerCmd(imagePath).exec();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Path searchForClassInSourceFiles(File sourceDir, String className) {
        try {
            return Files.walk(sourceDir.toPath())
                    .filter(path -> {
                        if (path.toString().endsWith(".java")) {
                            String pathSuffix = path.toString().substring(0, path.toString().lastIndexOf("."));
                            if (pathSuffix.endsWith(className)) {
                                return true;
                            }
                        }
                        return false;
                    }).findFirst().orElse(null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void getSourceFiles(DockerClient dockerClient, CreateContainerResponse container, File outputDir) {
        try (InputStream tarStream = dockerClient.copyArchiveFromContainerCmd(container.getId(), "/").exec();
             TarArchiveInputStream tarInput = new TarArchiveInputStream(tarStream)) {

            TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                String path = entry.getName();
                //System.out.println(path);
                if (!path.contains("src/")) {
                    continue;
                }



                if (entry.isDirectory()) {
                    File outputFile = new File(outputDir.getAbsolutePath() + entry.getName());
                    outputFile.mkdirs();
                } else {
                    if (path.endsWith("java") || path.endsWith(".jar")) {
                        extractWholeEntry(tarInput, entry, outputDir);
                    } else {
                        //System.out.println("Rejected "+entry.getName());
                    }
                }

                //extractEntry(tarInput, entry, outputDir, "");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void getDependencyFiles(DockerClient dockerClient, CreateContainerResponse container, File outputDir) {
        try (InputStream tarStream = dockerClient.copyArchiveFromContainerCmd(container.getId(), "/").exec();
             TarArchiveInputStream tarInput = new TarArchiveInputStream(tarStream)) {

            TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                String path = entry.getName();
                //System.out.println(path);
                if (!path.contains("tmp/dependencies")) {
                    continue;
                }

                if (entry.isDirectory()) {
                    File outputFile = new File(outputDir.getAbsolutePath() + entry.getName());
                    outputFile.mkdirs();
                } else {
                    if (path.endsWith("java") || path.endsWith(".jar")) {
                        extractWholeEntry(tarInput, entry, outputDir);
                    } else {
                        //System.out.println("Rejected "+entry.getName());
                    }
                }

                //extractEntry(tarInput, entry, outputDir, "");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void getFileFromContainer(DockerClient dockerClient, CreateContainerResponse container, String fileName, File outputDir, String outputNameModifier, int iteration) {
        try (InputStream tarStream = dockerClient.copyArchiveFromContainerCmd(container.getId(), "/").exec();
             TarArchiveInputStream tarInput = new TarArchiveInputStream(tarStream)) {

            TarArchiveEntry entry;
            boolean found = false;
            while ((entry = tarInput.getNextEntry()) != null) {
                String path = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                //System.out.println(path);
                if (!path.endsWith(fileName)) {
                    continue;
                }

                System.out.println("Found " + fileName + " in container, proceeding to download it into " + outputDir.getAbsolutePath());
                extractEntry(tarInput, entry, outputDir, outputNameModifier, iteration);
                found = true;
                break;
            }
            if (!found) {
                System.err.println("No file with name " + fileName + " found in container");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void replaceFileInContainer(DockerClient dockerClient, CreateContainerResponse container, Path filePath, String fileNameInContainer) {
        ByteArrayOutputStream tarOut = new ByteArrayOutputStream();
        TarArchiveOutputStream tos = new TarArchiveOutputStream(tarOut);

        try {
            byte[] fileContent = Files.readAllBytes(filePath);
            TarArchiveEntry entry = new TarArchiveEntry(fileNameInContainer.substring(fileNameInContainer.lastIndexOf("/") + 1));
            entry.setSize(fileContent.length);
            tos.putArchiveEntry(entry);
            tos.write(fileContent);
            tos.closeArchiveEntry();
            tos.close();

            ByteArrayInputStream tarStream = new ByteArrayInputStream(tarOut.toByteArray());

            String containerId = container.getId();
            String destPath = fileNameInContainer;
            destPath = destPath.substring(0, destPath.lastIndexOf("/"));


            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(tarStream)
                    .withRemotePath(destPath)
                    .exec();

            System.out.println("File " + fileNameInContainer + " replaced successfully!");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
