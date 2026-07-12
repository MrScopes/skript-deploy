package me.mrscopes.skriptdeploy.deploy;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class GitManager {
    public enum InitializationState { NOT_INITIALIZED, INITIALIZING, INITIALIZED, FAILED }

    public record GitChanges(List<String> changedScripts, List<String> deletedScripts) {
        public GitChanges {
            changedScripts = List.copyOf(changedScripts);
            deletedScripts = List.copyOf(deletedScripts);
        }

        public boolean changed() { return !changedScripts.isEmpty() || !deletedScripts.isEmpty(); }
        public boolean fullReloadRequired() { return !deletedScripts.isEmpty(); }
        public static GitChanges noChanges() { return new GitChanges(List.of(), List.of()); }
    }

    private final DeployConfig config;
    private final Path repositoryDirectory;
    private volatile InitializationState initializationState = InitializationState.NOT_INITIALIZED;
    private volatile String initializationFailure;

    public GitManager(DeployManager deployManager) {
        Objects.requireNonNull(deployManager, "deployManager cannot be null");
        this.config = Objects.requireNonNull(deployManager.getDeployConfig(), "deployConfig cannot be null");
        this.repositoryDirectory = config.getRepositoryDirectory().toAbsolutePath().normalize();
    }

    public InitializationState getInitializationState() { return initializationState; }
    public String getInitializationFailure() { return initializationFailure; }
    public boolean isInitialized() { return initializationState == InitializationState.INITIALIZED; }
    public boolean isInitializing() { return initializationState == InitializationState.INITIALIZING; }
    public boolean hasInitializationFailed() { return initializationState == InitializationState.FAILED; }

    // IO! Call async.
    public synchronized void initialize() throws IOException, GitAPIException {
        if (initializationState == InitializationState.INITIALIZING) throw new IllegalStateException("Git repository initialization is already running.");

        initializationState = InitializationState.INITIALIZING;
        initializationFailure = null;

        try {
            validateConfiguration();
            config.prepareDirectories();
            if (Files.notExists(repositoryDirectory)) cloneRepository();
            else validateRepositoryDirectory();
            initializationState = InitializationState.INITIALIZED;
        } catch (IOException | GitAPIException | RuntimeException exception) {
            initializationState = InitializationState.FAILED;
            initializationFailure = getFailureMessage(exception);
            throw exception;
        }
    }

    // IO! Call async.
    public GitChanges pullAndGetChanges() throws IOException, GitAPIException {
        if (!isInitialized()) throw new IllegalStateException("The Git repository is not initialized.");

        try (Git git = Git.open(repositoryDirectory.toFile())) {
            ObjectId oldHead = git.getRepository().resolve("HEAD");
            if (oldHead == null) throw new IllegalStateException("Could not resolve the current repository HEAD.");

            pull(git);

            ObjectId newHead = git.getRepository().resolve("HEAD");
            if (newHead == null) throw new IllegalStateException("Could not resolve repository HEAD after pulling.");
            if (oldHead.equals(newHead)) return GitChanges.noChanges();

            return compareCommits(git, oldHead, newHead);
        }
    }

    private void pull(Git git) throws GitAPIException {
        PullCommand command = git.pull().setRemote(config.getOrigin());
        CredentialsProvider credentialsProvider = config.getCredentialsProvider();
        if (credentialsProvider != null) command.setCredentialsProvider(credentialsProvider);

        PullResult result = command.call();
        if (!result.isSuccessful()) throw new IllegalStateException("Git pull did not complete successfully: " + result);
    }

    private GitChanges compareCommits(Git git, ObjectId oldHead, ObjectId newHead) throws IOException, GitAPIException {
        List<DiffEntry> entries;

        try (RevWalk revWalk = new RevWalk(git.getRepository()); ObjectReader reader = git.getRepository().newObjectReader()) {
            RevCommit oldCommit = revWalk.parseCommit(oldHead);
            RevCommit newCommit = revWalk.parseCommit(newHead);
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            oldTree.reset(reader, oldCommit.getTree().getId());
            newTree.reset(reader, newCommit.getTree().getId());
            entries = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
        }

        String scriptsPrefix = getRepositoryScriptsPrefix();
        Set<String> changedScripts = new LinkedHashSet<>();
        Set<String> deletedScripts = new LinkedHashSet<>();

        for (DiffEntry entry : entries) {
            switch (entry.getChangeType()) {
                case ADD, MODIFY, COPY -> addScript(changedScripts, entry.getNewPath(), scriptsPrefix);
                case DELETE -> addScript(deletedScripts, entry.getOldPath(), scriptsPrefix);
                case RENAME -> {
                    addScript(deletedScripts, entry.getOldPath(), scriptsPrefix);
                    addScript(changedScripts, entry.getNewPath(), scriptsPrefix);
                }
            }
        }

        if (changedScripts.isEmpty() && deletedScripts.isEmpty()) return GitChanges.noChanges();
        return new GitChanges(List.copyOf(changedScripts), List.copyOf(deletedScripts));
    }

    private void addScript(Set<String> scripts, String repositoryPath, String scriptsPrefix) {
        if (isDeployedScript(repositoryPath, scriptsPrefix)) scripts.add(removeScriptsPrefix(repositoryPath, scriptsPrefix));
    }

    private boolean isDeployedScript(String repositoryPath, String scriptsPrefix) {
        if (repositoryPath == null || repositoryPath.equals(DiffEntry.DEV_NULL)) return false;
        if (!repositoryPath.toLowerCase(Locale.ROOT).endsWith(".sk")) return false;
        return scriptsPrefix.isBlank() || repositoryPath.startsWith(scriptsPrefix + "/");
    }

    private String getRepositoryScriptsPrefix() {
        String prefix = config.getRepositoryScriptsFolder().toString().replace('\\', '/').trim();
        while (prefix.startsWith("/")) prefix = prefix.substring(1);
        while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        return prefix;
    }

    private String removeScriptsPrefix(String repositoryPath, String scriptsPrefix) {
        return scriptsPrefix.isBlank() ? repositoryPath : repositoryPath.substring(scriptsPrefix.length() + 1);
    }

    private void validateRepositoryDirectory() throws IOException, GitAPIException {
        if (!Files.isDirectory(repositoryDirectory)) throw new IllegalStateException("The configured repository path is not a directory: " + repositoryDirectory);
        if (isDirectoryEmpty(repositoryDirectory)) {
            cloneRepository();
            return;
        }
        validateLocalRepository();
    }

    private void validateConfiguration() {
        if (config.getRepositoryUrl().isBlank()) throw new IllegalStateException("repository.url cannot be blank.");
        if (config.getOrigin().isBlank()) throw new IllegalStateException("repository.origin cannot be blank.");
    }

    private void cloneRepository() throws GitAPIException {
        CloneCommand command = Git.cloneRepository().setURI(config.getRepositoryUrl()).setRemote(config.getOrigin()).setDirectory(repositoryDirectory.toFile());
        CredentialsProvider credentialsProvider = config.getCredentialsProvider();
        if (credentialsProvider != null) command.setCredentialsProvider(credentialsProvider);
        try (Git ignored = command.call()) {}
    }

    private void validateLocalRepository() throws IOException {
        try (Git git = Git.open(repositoryDirectory.toFile())) {
            String localRemoteUrl = getLocalRemoteUrl(git);
            String configuredUrl = normalizeRepositoryUrl(config.getRepositoryUrl());
            String actualUrl = normalizeRepositoryUrl(localRemoteUrl);

            if (!actualUrl.equals(configuredUrl)) {
                throw new IllegalStateException("The local repository remote does not match repository.url." + System.lineSeparator() + "Configured: " + config.getRepositoryUrl() + System.lineSeparator() + "Local: " + localRemoteUrl);
            }
        } catch (RepositoryNotFoundException exception) {
            throw new IllegalStateException("The configured repository folder is not empty, but it does not contain a valid Git repository: " + repositoryDirectory, exception);
        }
    }

    private String getLocalRemoteUrl(Git git) {
        StoredConfig storedConfig = git.getRepository().getConfig();
        String localRemoteUrl = storedConfig.getString("remote", config.getOrigin(), "url");
        if (localRemoteUrl == null || localRemoteUrl.isBlank()) throw new IllegalStateException("The local repository does not contain the configured remote '" + config.getOrigin() + "'.");
        return localRemoteUrl;
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        try (var contents = Files.list(directory)) { return contents.findAny().isEmpty(); }
    }

    private String normalizeRepositoryUrl(String url) {
        String normalized = url.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith(".git")) normalized = normalized.substring(0, normalized.length() - 4);
        return normalized;
    }

    private String getFailureMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    public Path getRepositoryDirectory() { return repositoryDirectory; }
}
