package me.mrscopes.skriptdeploy.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import me.mrscopes.skriptdeploy.SkriptDeploy;
import me.mrscopes.skriptdeploy.deploy.DeployManager;
import org.bukkit.command.CommandSender;

public final class DeployCommand {
    private final SkriptDeploy plugin;

    public DeployCommand(ReloadableRegistrarEvent<Commands> event) {
        this.plugin = SkriptDeploy.getInstance();
        event.registrar().register(buildCommand());
    }

    private LiteralCommandNode<CommandSourceStack> buildCommand() {
        return Commands.literal("deploy")
                .requires(source -> plugin.hasDeployPermission(source.getSender()))
                .executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    DeployManager manager = plugin.getDeployManager();

                    if (manager == null) {
                        sender.sendMessage("SkriptDeploy is not initialized.");
                        return 0;
                    }

                    manager.deploy(sender);
                    return 1;
                })
                .then(Commands.literal("reload").executes(context -> {
                    plugin.reloadDeploySystem(context.getSource().getSender());
                    return 1;
                }))
                .build();
    }
}
