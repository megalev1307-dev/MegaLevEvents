
package fun.megalev.events;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class MegaLevEvents extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("MegaLevEvents enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("MegaLevEvents disabled!");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (command.getName().equalsIgnoreCase("parkur")) {
            sender.sendMessage(
                "§eПаркур пока не настроен."
            );
            return true;
        }

        if (!command.getName().equalsIgnoreCase("event")) {
            return false;
        }

        if (!sender.hasPermission("megalevevents.admin")) {
            sender.sendMessage("§cНет прав!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6§lMegaLevEvents");
            sender.sendMessage("§e/event start vant");
            sender.sendMessage("§e/event start fever");
            sender.sendMessage("§e/event start casino");
            sender.sendMessage("§e/event npc");
            sender.sendMessage("§e/event parkourfinish");
            return true;
        }

        if (args[0].equalsIgnoreCase("start")) {
            if (args.length < 2) {
                sender.sendMessage(
                    "§cИспользуй: /event start <тип>"
                );
                return true;
            }

            List<String> types =
                List.of("vant", "fever", "casino");

            String type = args[1].toLowerCase();

            if (!types.contains(type)) {
                sender.sendMessage("§cНеизвестный ивент!");
                return true;
            }

            sender.sendMessage(
                "§eИвент " + type
                + " пока не реализован."
            );
            return true;
        }

        if (args[0].equalsIgnoreCase("npc")) {
            sender.sendMessage(
                "§eNPC пока не создан."
            );
            return true;
        }

        if (args[0].equalsIgnoreCase("parkourfinish")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(
                    "Команда только для игрока."
                );
                return true;
            }

            sender.sendMessage(
                "§eНаграды паркура пока не настроены."
            );
            return true;
        }

        sender.sendMessage("§cНеизвестная команда!");
        return true;
    }
}
