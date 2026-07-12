package me.mrscopes.skriptdeploy;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.mrscopes.skriptdeploy.command.DeployCommand;
import me.mrscopes.skriptdeploy.deploy.DeployManager;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkriptDeploy extends JavaPlugin {
    private static SkriptDeploy plugin;
    public static SkriptDeploy getInstance() { return plugin; }

    private DeployManager deployManager;
    public DeployManager getDeployManager() { return deployManager; }

    public boolean hasDeployPermission(CommandSender sender) {
        if (deployManager != null) return deployManager.hasDeployPermission(sender);
        String permission = getConfig().getString("permission", "skriptdeploy.deploy");
        return permission == null || permission.isBlank() || sender.hasPermission(permission);
    }

    private void createDeployManager() { deployManager = new DeployManager(this); }

    public void reloadDeploySystem(CommandSender sender) {
        DeployManager currentManager = deployManager;

        if (currentManager != null) {
            if (currentManager.isDeploying()) {
                sender.sendMessage("Cannot reload while a deployment is in progress.");
                return;
            }

            if (currentManager.getGitManager().isInitializing()) {
                sender.sendMessage("Cannot reload while the Git repository is initializing.");
                return;
            }
        }

        try {
            reloadConfig();
            createDeployManager();
        } catch (RuntimeException exception) {
            getLogger().severe("Failed to reload SkriptDeploy configuration: " + getExceptionMessage(exception));
            exception.printStackTrace();
            sender.sendMessage("Failed to reload configuration: " + getExceptionMessage(exception));
            return;
        }

        sender.sendMessage("Configuration reloaded. Initializing Git repository...");
        deployManager.initialize(() -> sender.sendMessage("SkriptDeploy reloaded successfully."), exception -> sender.sendMessage("Configuration reloaded, but Git initialization failed: " + getExceptionMessage(exception)));
    }

    @Override
    public void onEnable() {
        plugin = this;
        saveDefaultConfig();

        try {
            createDeployManager();
            deployManager.initialize();
        } catch (RuntimeException exception) {
            getLogger().severe("Failed to load SkriptDeploy configuration: " + getExceptionMessage(exception));
            exception.printStackTrace();
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, DeployCommand::new);
    }

    @Override
    public void onDisable() {
        deployManager = null;
        plugin = null;
    }

    private String getExceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
