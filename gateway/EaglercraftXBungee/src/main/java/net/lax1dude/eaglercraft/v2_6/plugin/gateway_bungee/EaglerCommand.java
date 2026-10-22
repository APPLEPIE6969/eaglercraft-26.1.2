package net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee;

import java.util.function.Supplier;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;

/**
 * {@code /eagler} status command for EaglercraftXBungee.
 *
 * <p>Reports whether the WebSocket listener is running, the port it is bound
 * to, and how many handshakes were completed this session. Useful to confirm
 * the gateway came up without digging through the proxy log.</p>
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
    }
}
