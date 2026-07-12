package me.mrscopes.skriptdeploy.deploy;

import me.mrscopes.skriptdeploy.SkriptDeploy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class DeployManager {
    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private final SkriptDeploy plugin;
    private final DeployConfig deployConfig;
    private final GitManager gitManager;
    private final AtomicBoolean deploying = new AtomicBoolean(false);

    public DeployManager(SkriptDeploy plugin) {
        this.plugin = plugin;
        this.deployConfig = new DeployConfig(plugin);
        this.gitManager = new GitManager(this);
    }

    public DeployConfig getDeployConfig() { return deployConfig; }
    public GitManager getGitManager() { return gitManager; }
    public boolean isDeploying() { return deploying.get(); }

    public void initialize() {
        initialize(() -> plugin.getLogger().info("Git repository initialized successfully."), exception -> plugin.getLogger().severe("Failed to initialize Git repository: " + getExceptionMessage(exception)));
    }

    public void initialize(Runnable success, Consumer<Exception> failure) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                gitManager.initialize();
                Bukkit.getScheduler().runTask(plugin, success);
            } catch (Exception exception) {
                plugin.getLogger().severe("Git repository initialization failed: " + getExceptionMessage(exception));
                exception.printStackTrace();
                Bukkit.getScheduler().runTask(plugin, () -> failure.accept(exception));
            }
        });
    }

    public void deploy(CommandSender sender) {
        switch (gitManager.getInitializationState()) {
            case NOT_INITIALIZED -> {
                sender.sendMessage("The Git repository has not been initialized.");
                return;
            }
            case INITIALIZING -> {
                sender.sendMessage("The Git repository is still initializing.");
                return;
            }
            case FAILED -> {
                String reason = gitManager.getInitializationFailure();
                sender.sendMessage(reason == null ? "The Git repository failed to initialize. Check the console." : "The Git repository failed to initialize: " + reason);
                return;
            }
            case INITIALIZED -> {}
        }

        if (!deploying.compareAndSet(false, true)) {
            sender.sendMessage("A deployment is already in progress.");
            return;
        }

        String deployerName = sender.getName();
        notifyDeployers(deployerName + " is deploying.");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                GitManager.GitChanges changes = gitManager.pullAndGetChanges();
                if (changes.changed()) applyChanges(changes);

                Bukkit.getScheduler().runTask(plugin, () -> finishDeployment(sender, deployerName, changes));
            } catch (Exception exception) {
                plugin.getLogger().severe("Deployment failed: " + getExceptionMessage(exception));
                exception.printStackTrace();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        sender.sendMessage("Deploy failed: " + getExceptionMessage(exception));
                        notifyDeployers("Deployment by " + deployerName + " failed.");
                    } finally {
                        deploying.set(false);
                    }
                });
            }
        });
    }

    private void finishDeployment(CommandSender sender, String deployerName, GitManager.GitChanges changes) {
        try {
            reloadScripts(sender, changes);
            notifyDeployers(changes.changed() ? "Deploy finished successfully." : "Deploy finished. No script changes were found.");
        } catch (Exception exception) {
            plugin.getLogger().severe("Files were deployed, but Skript reload failed: " + getExceptionMessage(exception));
            exception.printStackTrace();
            sender.sendMessage("Files were deployed, but Skript reload failed: " + getExceptionMessage(exception));
            notifyDeployers("Deployment by " + deployerName + " failed during Skript reload.");
        } finally {
            deploying.set(false);
        }
    }

    // IO! Called from the asynchronous deployment task.
    private void applyChanges(GitManager.GitChanges changes) throws IOException {
        Path repositoryScripts = deployConfig.getRepositoryScriptsDirectory().toAbsolutePath().normalize();
        Path liveScripts = deployConfig.getScriptsDirectory();
        Path backupDirectory = deployConfig.getBackupsDirectory().resolve(LocalDateTime.now().format(BACKUP_FORMAT));
        boolean backedUpAnything = false;

        for (String script : changes.changedScripts()) {
            Path liveFile = resolveSafely(liveScripts, script);
            if (Files.isRegularFile(liveFile)) {
                backupFile(liveScripts, liveFile, backupDirectory);
                backedUpAnything = true;
            }
        }

        for (String script : changes.deletedScripts()) {
            Path liveFile = resolveSafely(liveScripts, script);
            if (Files.isRegularFile(liveFile)) {
                backupFile(liveScripts, liveFile, backupDirectory);
                backedUpAnything = true;
            }
        }

        for (String script : changes.changedScripts()) {
            Path source = resolveSafely(repositoryScripts, script);
            Path destination = resolveSafely(liveScripts, script);
            if (!Files.isRegularFile(source)) throw new IOException("Repository script does not exist: " + source);
            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }

        for (String script : changes.deletedScripts()) {
            Path destination = resolveSafely(liveScripts, script);
            Files.deleteIfExists(destination);
            deleteEmptyParents(destination.getParent(), liveScripts);
        }

        if (!backedUpAnything) Files.deleteIfExists(backupDirectory);
    }

    private void backupFile(Path liveScripts, Path liveFile, Path backupDirectory) throws IOException {
        Path backupFile = backupDirectory.resolve(liveScripts.relativize(liveFile)).normalize();
        if (!backupFile.startsWith(backupDirectory)) throw new IOException("Backup path escaped the backup directory: " + backupFile);
        if (backupFile.getParent() != null) Files.createDirectories(backupFile.getParent());
        Files.copy(liveFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path resolveSafely(Path root, String relativePath) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(normalizedRoot)) throw new IllegalArgumentException("Script path escapes its configured folder: " + relativePath);
        return resolved;
    }

    private void deleteEmptyParents(Path directory, Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        while (directory != null && !directory.equals(normalizedRoot) && directory.startsWith(normalizedRoot)) {
            if (!Files.isDirectory(directory) || !isDirectoryEmpty(directory)) return;
            Files.deleteIfExists(directory);
            directory = directory.getParent();
        }
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        try (var files = Files.list(directory)) { return files.findAny().isEmpty(); }
    }

    private void reloadScripts(CommandSender sender, GitManager.GitChanges changes) {
        if (!changes.changed()) {
            sender.sendMessage("No changed scripts were found.");
            return;
        }

        if (changes.fullReloadRequired()) {
            sender.sendMessage("A script was deleted or renamed. Reloading all scripts...");
            dispatchSkriptCommand(sender, "sk reload scripts");
            return;
        }

        for (String script : changes.changedScripts()) {
            sender.sendMessage("Reloading " + script + "...");
            dispatchSkriptCommand(sender, "sk reload " + script);
        }
    }

    private void dispatchSkriptCommand(CommandSender sender, String command) {
        if (!Bukkit.dispatchCommand(sender, command)) throw new IllegalStateException("Command could not be dispatched: /" + command);
    }

    private void notifyDeployers(String message) { getDeployers().forEach(player -> player.sendMessage(message)); }
    private Stream<? extends Player> getDeployers() { return Bukkit.getOnlinePlayers().stream().filter(this::hasDeployPermission); }

    public boolean hasDeployPermission(CommandSender sender) {
        String permission = deployConfig.getPermission();
        return permission.isBlank() || sender.hasPermission(permission);
    }

    private String getExceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
