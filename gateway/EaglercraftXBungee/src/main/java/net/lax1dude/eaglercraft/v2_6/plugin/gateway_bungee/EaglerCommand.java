package net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee;

import java.util.function.Supplier;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.plugin.Command;

/**
 * {@code /eagler} status command for EaglercraftXBungee.
 *
 * <p>Reports whether the WebSocket listener is running, the port it is bound
 * to, how many handshakes were completed, and (milestone 2) the list of
 * online Eagler players with their bridge states and backend servers.</p>
 */
public class EaglerCommand extends Command {

    private final Supplier<EaglerWSListener> listener;

    public EaglerCommand(EaglercraftXBungee plugin, Supplier<EaglerWSListener> listener) {
        super("eagler", null, "eaglercraft");
        this.listener = listener;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        EaglerWSListener l = listener.get();
        if (l == null) {
            sender.sendMessage("EaglercraftXBungee: listener not started");
            return;
        }
        sender.sendMessage("EaglercraftXBungee " + EaglercraftXBungee.SERVER_BRAND
                + " - ws://0.0.0.0:" + l.getPort()
                + ", " + l.getTotalAccepted() + " handshake(s) this session");
        EaglerSessionRegistry registry = l.getRegistry();
        if (registry != null) {
            java.util.Collection<EaglerPlayerSession> sessions = registry.all();
            sender.sendMessage("Eagler players online: " + sessions.size());
            for (EaglerPlayerSession s : sessions) {
                Server server = s.getServer();
                sender.sendMessage(" - " + s.getName() + " (" + s.getUniqueId() + ")"
                        + " bridge=" + (server != null && server.isConnected() ? "up" : "down")
                        + " server=" + (server != null ? server.getInfo().getName() : "(none)"));
            }
        }
    }
}
