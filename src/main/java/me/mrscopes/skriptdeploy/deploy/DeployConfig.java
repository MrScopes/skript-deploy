package me.mrscopes.skriptdeploy.deploy;

import me.mrscopes.skriptdeploy.SkriptDeploy;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DeployConfig {
    private final SkriptDeploy plugin;

    private String repositoryUrl;
    public String getRepositoryUrl() { return repositoryUrl; }

    private String origin;
    public String getOrigin() { return origin; }

    private Path repositoryDirectory;
    public Path getRepositoryDirectory() { return repositoryDirectory; }

    private Path repositoryScriptsFolder;
    public Path getRepositoryScriptsFolder() { return repositoryScriptsFolder; }

    private Path scriptsDirectory;
    public Path getScriptsDirectory() { return scriptsDirectory; }

    private Path backupsDirectory;
    public Path getBackupsDirectory() { return backupsDirectory; }

    private String permission;
    public String getPermission() { return permission; }

    private String username;
    private String secret;

    public DeployConfig(SkriptDeploy plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        plugin.reloadConfig();

        this.repositoryUrl = plugin.getConfig().getString("repository.url", "").trim();
        this.origin = plugin.getConfig().getString("repository.origin", "origin").trim();
        this.repositoryDirectory = Path.of(plugin.getConfig().getString("repository.local-folder", "plugins/skript-deploy/repository")).toAbsolutePath().normalize();
        this.repositoryScriptsFolder = Path.of(plugin.getConfig().getString("repository.scripts-folder", "")).normalize();
        this.scriptsDirectory = Path.of(plugin.getConfig().getString("scripts-folder", "plugins/Skript/scripts")).toAbsolutePath().normalize();
        this.backupsDirectory = Path.of(plugin.getConfig().getString("backups-folder", "plugins/skript-deploy/backups")).toAbsolutePath().normalize();
        this.permission = plugin.getConfig().getString("permission", "skriptdeploy.deploy").trim();
        this.username = plugin.getConfig().getString("repository.credentials.username", "").trim();
        this.secret = plugin.getConfig().getString("repository.credentials.secret", "");

        validateConfig();
    }

    private void validateConfig() {
        if (repositoryUrl.isBlank()) throw new IllegalStateException("repository.url cannot be blank.");
        if (origin.isBlank()) throw new IllegalStateException("repository.origin cannot be blank.");
        if (repositoryScriptsFolder.isAbsolute()) throw new IllegalStateException("repository.scripts-folder must be relative to the repository.");
        if (repositoryScriptsFolder.startsWith("..")) throw new IllegalStateException("repository.scripts-folder cannot point outside the repository.");
        //if (repositoryDirectory.equals(scriptsDirectory)) throw new IllegalStateException("repository.local-folder and scripts-folder cannot be the same folder.");
    }

    void prepareDirectories() {
        try {
            Path repositoryParent = repositoryDirectory.getParent();
            if (repositoryParent != null) Files.createDirectories(repositoryParent);
            Files.createDirectories(scriptsDirectory);
            Files.createDirectories(backupsDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the configured directories.", exception);
        }
    }

    public Path getRepositoryScriptsDirectory() { return repositoryDirectory.resolve(repositoryScriptsFolder).normalize(); }

    public CredentialsProvider getCredentialsProvider() {
        if (secret.isBlank()) return null;
        return new UsernamePasswordCredentialsProvider(username, secret);
    }
}
